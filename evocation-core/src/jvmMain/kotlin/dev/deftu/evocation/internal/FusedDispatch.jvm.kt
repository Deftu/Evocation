package dev.deftu.evocation.internal

import dev.deftu.evocation.DispatchStrategy
import dev.deftu.evocation.EventFilter
import dev.deftu.evocation.EventHandler
import dev.deftu.evocation.ExceptionHandler
import dev.deftu.evocation.SubscriberMethod
import dev.deftu.evocation.invokers.ClassDefiners
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger

/**
 * Generates a class per event type whose `dispatch` calls every subscriber from
 * its own call site, so each one sees a single receiver type and the JIT can
 * inline through it.
 *
 * The shape it emits, for a listener, a lambda and a filtered subscriber:
 *
 * ```java
 * public void dispatch(Object event, ExceptionHandler handler) {
 *     try { t0.onMessage((Message) event); }   catch (Exception e) { handler.handle(e); }
 *     try { t1.handle(event); }                catch (Exception e) { handler.handle(e); }
 *     if (f2.matches(event)) {
 *         try { m2.invoke(t2, event); }        catch (Exception e) { handler.handle(e); }
 *     }
 * }
 * ```
 *
 * Nothing here is required for correctness; anything it declines runs on the
 * interpreted loop instead.
 */
internal actual object FusedDispatchers {
    private val counter = AtomicInteger()

    private val DISPATCH_INTERFACE = Type.getInternalName(FusedDispatch::class.java)
    private val EXCEPTION_HANDLER = Type.getInternalName(ExceptionHandler::class.java)
    private val EVENT_HANDLER = Type.getInternalName(EventHandler::class.java)
    private val EVENT_FILTER = Type.getInternalName(EventFilter::class.java)
    private val SUBSCRIBER_METHOD = Type.getInternalName(SubscriberMethod::class.java)

    actual fun create(
        registrations: Array<Registration>,
        strategy: DispatchStrategy.Generated
    ): FusedDispatch? {
        if (registrations.size < strategy.minSubscribers) return null
        if (registrations.size > strategy.maxSubscribers) return null

        val calls = registrations.map { describe(it) ?: return null }
        val neighbour = calls.first().visibilityAnchor
        if (!isVisible(calls, neighbour)) return null

        return try {
            val name = "dev/deftu/evocation/generated/FusedDispatch\$${counter.incrementAndGet()}"
            val bytes = generate(name, calls)
            val type = ClassDefiners.default.define(name.replace('/', '.'), bytes, neighbour) ?: return null
            val constructor = type.getDeclaredConstructor(Array<Any>::class.java)
            constructor.isAccessible = true
            constructor.newInstance(calls.map { it.slots }.flatten().toTypedArray()) as FusedDispatch
        } catch (ignored: Throwable) {
            // Any failure here is recoverable; the caller uses the loop.
            null
        }
    }

    /**
     * How to call one subscriber, or null when it cannot be generated.
     *
     * A weak subscriber is the one kind left out: its target is resolved per
     * post and may be gone, and the bus needs to hear about that so it can
     * prune. Deciding that inside generated code would mean reporting back.
     */
    private fun describe(registration: Registration): Call? {
        if (registration.isWeak) return null
        val target = registration.target() ?: return null
        val filter = registration.filter
        val method = registration.descriptor as? Method

        return when {
            // An annotated subscriber whose method the generated class can see.
            method != null && isCallable(method) -> Call.Direct(method, target, filter)

            // A lambda from `on`, whose target is the handler itself.
            method == null && target is EventHandler<*> -> Call.Handler(target, filter)

            // Anything else, through the SubscriberMethod its invoker produced.
            else -> Call.Indirect(registration.subscriberMethod, target, filter)
        }
    }

    /**
     * The generated class is a stranger to the subscriber's package, so a direct
     * call only works against public methods on public types.
     */
    private fun isCallable(method: Method): Boolean =
        Modifier.isPublic(method.modifiers) &&
            Modifier.isPublic(method.declaringClass.modifiers) &&
            Modifier.isPublic(method.parameterTypes[0].modifiers)

    /**
     * Every class the generated code names has to resolve to the same type
     * through the defining loader, or it would define and then fail with
     * NoClassDefFoundError on first use.
     */
    private fun isVisible(calls: List<Call>, neighbour: Class<*>): Boolean {
        val loader = neighbour.classLoader ?: ClassLoader.getSystemClassLoader() ?: return false
        val required = buildList {
            add(FusedDispatch::class.java)
            add(ExceptionHandler::class.java)
            add(EventHandler::class.java)
            add(EventFilter::class.java)
            add(SubscriberMethod::class.java)
            for (call in calls) if (call is Call.Direct) {
                add(call.method.declaringClass)
                add(call.method.parameterTypes[0])
            }
        }

        return required.all { type ->
            runCatching { Class.forName(type.name, false, loader) }.getOrNull() === type
        }
    }

    private fun generate(name: String, calls: List<Call>): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
            name,
            null,
            "java/lang/Object",
            arrayOf(DISPATCH_INTERFACE)
        )

        val fields = fieldsOf(calls)
        for (field in fields) {
            writer.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL, field.name, field.descriptor, null, null)
                .visitEnd()
        }

        writeConstructor(writer, name, fields)
        writeDispatch(writer, name, calls, fields)

        writer.visitEnd()
        return writer.toByteArray()
    }

    /** One field per slot, in the order the constructor's array supplies them. */
    private fun fieldsOf(calls: List<Call>): List<Field> {
        val fields = ArrayList<Field>()
        calls.forEachIndexed { index, call ->
            when (call) {
                is Call.Direct -> if (!call.isStatic) {
                    fields.add(Field("t$index", Type.getDescriptor(call.method.declaringClass)))
                }
                is Call.Handler -> fields.add(Field("t$index", "L$EVENT_HANDLER;"))
                is Call.Indirect -> {
                    fields.add(Field("m$index", "L$SUBSCRIBER_METHOD;"))
                    fields.add(Field("t$index", "Ljava/lang/Object;"))
                }
            }

            if (call.filter != null) fields.add(Field("f$index", "L$EVENT_FILTER;"))
        }

        return fields
    }

    private fun writeConstructor(writer: ClassWriter, owner: String, fields: List<Field>) {
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "([Ljava/lang/Object;)V", null, null)
        method.visitCode()
        method.visitVarInsn(Opcodes.ALOAD, 0)
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)

        fields.forEachIndexed { index, field ->
            method.visitVarInsn(Opcodes.ALOAD, 0)
            method.visitVarInsn(Opcodes.ALOAD, 1)
            method.visitIntInsn(Opcodes.SIPUSH, index)
            method.visitInsn(Opcodes.AALOAD)
            method.visitTypeInsn(Opcodes.CHECKCAST, Type.getType(field.descriptor).internalName)
            method.visitFieldInsn(Opcodes.PUTFIELD, owner, field.name, field.descriptor)
        }

        method.visitInsn(Opcodes.RETURN)
        method.visitMaxs(0, 0)
        method.visitEnd()
    }

    private fun writeDispatch(writer: ClassWriter, owner: String, calls: List<Call>, fields: List<Field>) {
        val method = writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "dispatch",
            "(Ljava/lang/Object;L$EXCEPTION_HANDLER;)V",
            null,
            null
        )

        method.visitCode()

        val byName = fields.associateBy { it.name }
        calls.forEachIndexed { index, call ->
            val next = Label()

            if (call.filter != null) {
                val field = byName.getValue("f$index")
                method.visitVarInsn(Opcodes.ALOAD, 0)
                method.visitFieldInsn(Opcodes.GETFIELD, owner, field.name, field.descriptor)
                method.visitVarInsn(Opcodes.ALOAD, 1)
                method.visitMethodInsn(Opcodes.INVOKEINTERFACE, EVENT_FILTER, "matches", "(Ljava/lang/Object;)Z", true)
                method.visitJumpInsn(Opcodes.IFEQ, next)
            }

            val start = Label()
            val end = Label()
            val catch = Label()
            method.visitTryCatchBlock(start, end, catch, "java/lang/Exception")

            method.visitLabel(start)
            emitCall(method, owner, index, call, byName)
            method.visitLabel(end)
            method.visitJumpInsn(Opcodes.GOTO, next)

            method.visitLabel(catch)
            method.visitVarInsn(Opcodes.ASTORE, 3)
            method.visitVarInsn(Opcodes.ALOAD, 2)
            method.visitVarInsn(Opcodes.ALOAD, 3)
            method.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                EXCEPTION_HANDLER,
                "handle",
                "(Ljava/lang/Exception;)V",
                true
            )

            method.visitLabel(next)
        }

        method.visitInsn(Opcodes.RETURN)
        method.visitMaxs(0, 0)
        method.visitEnd()
    }

    private fun emitCall(
        method: MethodVisitor,
        owner: String,
        index: Int,
        call: Call,
        fields: Map<String, Field>
    ) {
        when (call) {
            is Call.Direct -> {
                val field = fields.getValue("t$index")
                val declaring = Type.getInternalName(call.method.declaringClass)
                val isStatic = Modifier.isStatic(call.method.modifiers)

                if (!isStatic) {
                    method.visitVarInsn(Opcodes.ALOAD, 0)
                    method.visitFieldInsn(Opcodes.GETFIELD, owner, field.name, field.descriptor)
                }

                method.visitVarInsn(Opcodes.ALOAD, 1)
                method.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(call.method.parameterTypes[0]))

                val opcode = when {
                    isStatic -> Opcodes.INVOKESTATIC
                    call.method.declaringClass.isInterface -> Opcodes.INVOKEINTERFACE
                    else -> Opcodes.INVOKEVIRTUAL
                }

                method.visitMethodInsn(
                    opcode,
                    declaring,
                    call.method.name,
                    Type.getMethodDescriptor(call.method),
                    opcode == Opcodes.INVOKEINTERFACE
                )
            }

            is Call.Handler -> {
                val field = fields.getValue("t$index")
                method.visitVarInsn(Opcodes.ALOAD, 0)
                method.visitFieldInsn(Opcodes.GETFIELD, owner, field.name, field.descriptor)
                method.visitVarInsn(Opcodes.ALOAD, 1)
                method.visitMethodInsn(Opcodes.INVOKEINTERFACE, EVENT_HANDLER, "handle", "(Ljava/lang/Object;)V", true)
            }

            is Call.Indirect -> {
                val handle = fields.getValue("m$index")
                val target = fields.getValue("t$index")
                method.visitVarInsn(Opcodes.ALOAD, 0)
                method.visitFieldInsn(Opcodes.GETFIELD, owner, handle.name, handle.descriptor)
                method.visitVarInsn(Opcodes.ALOAD, 0)
                method.visitFieldInsn(Opcodes.GETFIELD, owner, target.name, target.descriptor)
                method.visitVarInsn(Opcodes.ALOAD, 1)
                method.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE,
                    SUBSCRIBER_METHOD,
                    "invoke",
                    "(Ljava/lang/Object;Ljava/lang/Object;)V",
                    true
                )
            }
        }
    }

    private class Field(val name: String, val descriptor: String)

    private sealed class Call(val filter: EventFilter<Any>?) {
        /** Anchors which loader the generated class is defined against. */
        abstract val visibilityAnchor: Class<*>

        /** Field values, in the order [fieldsOf] declares them. */
        abstract val slots: List<Any>

        class Direct(val method: Method, private val target: Any, filter: EventFilter<Any>?) : Call(filter) {
            val isStatic: Boolean = Modifier.isStatic(method.modifiers)

            override val visibilityAnchor: Class<*> get() = method.declaringClass

            // A static subscriber has no receiver to hold. Its registration
            // target is the declaring class itself, which must not reach a
            // field typed as an instance of it.
            override val slots: List<Any>
                get() = if (isStatic) listOfNotNull(filter) else listOfNotNull(target, filter)
        }

        class Handler(private val handler: Any, filter: EventFilter<Any>?) : Call(filter) {
            override val visibilityAnchor: Class<*> get() = handler.javaClass
            override val slots: List<Any>
                get() = listOfNotNull(handler, filter)
        }

        class Indirect(
            private val method: SubscriberMethod,
            private val target: Any,
            filter: EventFilter<Any>?
        ) : Call(filter) {
            override val visibilityAnchor: Class<*> get() = target.javaClass
            override val slots: List<Any>
                get() = listOfNotNull(method, target, filter)
        }
    }
}
