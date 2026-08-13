package dev.deftu.evocation.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Variance
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.joinToCode
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Generates registration code for `@EventSubscriber` functions.
 *
 * The JVM can find these reflectively at runtime. Nothing else can: Kotlin/JS
 * and Kotlin/Native have no member enumeration and no runtime annotations, so on
 * those targets this processor writes out the equivalent registration at compile
 * time.
 *
 * For a class `Foo` it emits a direct entry point:
 *
 * ```kotlin
 * public fun AbstractEventBus.registerSubscribers(listener: Foo, weak: Boolean = false): Subscription
 * ```
 *
 * plus a binding that `installGeneratedSubscribers` puts into
 * `GeneratedSubscribers`, which is what lets the common `register` find it.
 */
public class EventSubscriberProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {
    private var installerWritten = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(ANNOTATION).toList()
        val (valid, deferred) = symbols.partition { it.validate() }

        val owners = LinkedHashSet<KSClassDeclaration>()
        for (function in valid.filterIsInstance<KSFunctionDeclaration>()) {
            val owner = function.parentDeclaration as? KSClassDeclaration
            if (owner == null) {
                logger.error("@EventSubscriber is only supported on functions declared in a class or object.", function)
                continue
            }

            owners.add(owner)
        }

        val generated = owners.filter { generate(it) }
        if (generated.isNotEmpty() && !installerWritten) {
            installerWritten = true
            generateInstaller(generated)
        }

        return deferred
    }

    private fun generate(owner: KSClassDeclaration): Boolean {
        val subscribers = subscriberFunctions(owner).mapNotNull { describe(owner, it) }
        if (subscribers.isEmpty()) return false

        val ownerName = owner.toClassName()

        val registrations = subscribers.map { subscriber ->
            CodeBlock.builder()
                .add("registerHandler(\n")
                .indent()
                .add("eventType = %T::class,\n", subscriber.eventType)
                .add("priority = %T.%N,\n", EVENT_PRIORITY, subscriber.priority)
                .add("target = listener,\n")
                .beginControlFlow("method = %T { target, event ->", SUBSCRIBER_METHOD)
                .add("(target as %T).%N(event as %T)\n", ownerName, subscriber.functionName, subscriber.eventType)
                .endControlFlowWithComma()
                .add("weak = weak\n")
                .unindent()
                .add(")")
                .build()
        }

        val register = FunSpec.builder("registerSubscribers")
            .receiver(ABSTRACT_EVENT_BUS)
            .addParameter("listener", ownerName)
            .addParameter(ParameterSpec.builder("weak", BOOLEAN).defaultValue("false").build())
            .returns(SUBSCRIPTION)
            .addStatement("checkNotRegistered(listener)")
            .addCode(
                CodeBlock.builder()
                    .add("return listOf(\n")
                    .indent()
                    .add(registrations.joinToCode(",\n"))
                    .add("\n")
                    .unindent()
                    .add(").%M()\n", COMBINED)
                    .build()
            )
            .build()

        val install = FunSpec.builder(installerName(owner))
            .addModifiers(KModifier.INTERNAL)
            .addCode(
                CodeBlock.builder()
                    .beginControlFlow("%T.install(%T::class) { bus, listener, weak ->", GENERATED_SUBSCRIBERS, ownerName)
                    .addStatement("bus.registerSubscribers(listener as %T, weak)", ownerName)
                    .endControlFlow()
                    .build()
            )
            .build()

        FileSpec.builder(ownerName.packageName, "${owner.simpleName.asString()}Subscribers")
            .indent(INDENT)
            .addFileComment("Generated by evocation-ksp. Do not edit.")
            .addFunction(install)
            .addFunction(register)
            .build()
            .writeTo(codeGenerator, aggregating = false, originatingKSFiles = listOfNotNull(owner.containingFile))

        return true
    }

    /**
     * Writes the function that puts every generated binding into
     * `GeneratedSubscribers`.
     *
     * Needed because nothing on JS or Native runs a declaration's initializer
     * until something references it, so the bindings cannot install themselves.
     */
    private fun generateInstaller(owners: List<KSClassDeclaration>) {
        val body = CodeBlock.builder()
        for (owner in owners) {
            body.addStatement("%M()", MemberName(owner.toClassName().packageName, installerName(owner)))
        }

        FileSpec.builder(INSTALLER_PACKAGE, "GeneratedSubscriberInstaller")
            .indent(INDENT)
            .addFileComment("Generated by evocation-ksp. Do not edit.")
            .addFunction(
                FunSpec.builder("installGeneratedSubscribers")
                    .addKdoc("Registers every generated subscriber binding. Call once, before anything registers.")
                    .addCode(body.build())
                    .build()
            )
            .build()
            .writeTo(codeGenerator, aggregating = true, originatingKSFiles = owners.mapNotNull { it.containingFile })
    }

    /**
     * Every subscriber function callable on [owner], including inherited ones,
     * with an override represented by the implementation that runs.
     *
     * The JVM's reflective `register` walks the hierarchy, so this walks it too;
     * the two registration paths have to agree on what a listener subscribes to.
     */
    private fun subscriberFunctions(owner: KSClassDeclaration): List<KSFunctionDeclaration> {
        val found = LinkedHashMap<String, KSFunctionDeclaration>()

        for (function in owner.getAllFunctions()) {
            if (!function.isAnnotated()) continue
            val signature = function.simpleName.asString() +
                function.parameters.joinToString(",", "(", ")") {
                    it.type.resolve().declaration.qualifiedName?.asString() ?: "?"
                }

            found.putIfAbsent(signature, function)
        }

        return found.values.toList()
    }

    private fun KSFunctionDeclaration.isAnnotated(): Boolean = annotatedDeclarations().any()

    /**
     * This function and the ones it overrides that carry the annotation.
     *
     * Kotlin does not inherit annotations onto an override, so a subclass that
     * overrides an annotated function without repeating the annotation is still
     * a subscriber. Reflection sees this because it finds the base declaration;
     * here it means walking the overridee chain.
     */
    private fun KSFunctionDeclaration.annotatedDeclarations(): Sequence<KSFunctionDeclaration> =
        generateSequence(this) { it.findOverridee() as? KSFunctionDeclaration }
            .filter { function ->
                function.annotations.any {
                    it.annotationType.resolve().declaration.qualifiedName?.asString() == ANNOTATION
                }
            }

    private fun describe(owner: KSClassDeclaration, function: KSFunctionDeclaration): Subscriber? {
        if (owner.classKind != ClassKind.CLASS && owner.classKind != ClassKind.OBJECT) {
            logger.error("@EventSubscriber must be declared in a class or object, not a ${owner.classKind}.", function)
            return null
        }

        if (function.parameters.size != 1) {
            logger.error(
                "@EventSubscriber function takes ${function.parameters.size} parameters. " +
                    "A subscriber takes exactly one, the event.",
                function
            )

            return null
        }

        val returnType = function.returnType?.resolve()
        if (returnType != null && returnType.declaration.simpleName.asString() != "Unit") {
            logger.error(
                "@EventSubscriber function returns ${returnType.declaration.simpleName.asString()}. " +
                    "A subscriber returns nothing.",
                function
            )

            return null
        }

        val eventType = function.parameters.first().type.resolve()
        if (eventType.arguments.any { it.variance != Variance.STAR }) {
            logger.error(
                "@EventSubscriber function subscribes to $eventType, but type arguments are erased at " +
                    "runtime, so this would also receive every other parameterisation. Subscribe to the " +
                    "star-projected type and narrow it with an EventFilter, or check inside the subscriber.",
                function
            )

            return null
        }

        return Subscriber(
            functionName = function.simpleName.asString(),
            eventType = function.parameters.first().type.toTypeName(),
            priority = function.priority()
        )
    }

    /** Read from wherever the annotation actually sits, base or override. */
    private fun KSFunctionDeclaration.priority(): String {
        val declaration = annotatedDeclarations().firstOrNull() ?: return DEFAULT_PRIORITY
        val annotation = declaration.annotations.first {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == ANNOTATION
        }

        return annotation.arguments
            .firstOrNull { it.name?.asString() == "priority" }
            ?.value
            ?.toString()
            ?.substringAfterLast('.')
            ?: DEFAULT_PRIORITY
    }

    /** Unique per class, so the installer can reach it by name. */
    private fun installerName(owner: KSClassDeclaration): String =
        "install${owner.simpleName.asString()}Subscribers"

    /** [CodeBlock.Builder.endControlFlow] leaves no comma, and an argument needs one. */
    private fun CodeBlock.Builder.endControlFlowWithComma(): CodeBlock.Builder {
        unindent()
        add("},\n")
        return this
    }

    private class Subscriber(
        val functionName: String,
        val eventType: TypeName,
        val priority: String
    )

    private companion object {
        // Deliberately not const: a const val in a companion becomes a public
        // static field on the enclosing class, even when the companion is private.
        val ANNOTATION = "dev.deftu.evocation.EventSubscriber"
        val INSTALLER_PACKAGE = "dev.deftu.evocation.generated"
        val DEFAULT_PRIORITY = "NORMAL"
        val PACKAGE = "dev.deftu.evocation"

        /** KotlinPoet defaults to two spaces; the rest of the project uses four. */
        val INDENT = "    "

        val ABSTRACT_EVENT_BUS = ClassName(PACKAGE, "AbstractEventBus")
        val EVENT_PRIORITY = ClassName(PACKAGE, "EventPriority")
        val SUBSCRIBER_METHOD = ClassName(PACKAGE, "SubscriberMethod")
        val SUBSCRIPTION = ClassName(PACKAGE, "Subscription")
        val GENERATED_SUBSCRIBERS = ClassName(PACKAGE, "GeneratedSubscribers")
        val COMBINED = MemberName(PACKAGE, "combined")
    }
}

public class EventSubscriberProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        EventSubscriberProcessor(environment.codeGenerator, environment.logger)
}
