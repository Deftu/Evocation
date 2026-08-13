package dev.deftu.evocation

import dev.deftu.evocation.internal.Registration
import dev.deftu.evocation.invokers.Invoker
import dev.deftu.evocation.invokers.Invokers
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.WildcardType

/**
 * Every [EventSubscriber] function reachable from a class, most-derived first.
 *
 * Walks superclasses and interfaces, so an override is bound once, to the
 * implementation that would actually run.
 *
 * Cached because the answer never changes for a class, while `declaredMethods`
 * clones its array on every call. [ClassValue] keys on the class itself, so
 * unloading a classloader drops its entries with it.
 */
internal object SubscriberMethods : ClassValue<List<Method>>() {
    override fun computeValue(type: Class<*>): List<Method> {
        val found = LinkedHashMap<String, Method>()

        fun scan(clazz: Class<*>) {
            for (method in clazz.declaredMethods) {
                if (method.getAnnotation(EventSubscriber::class.java) == null) continue
                val signature = "${method.name}(${method.parameterTypes.joinToString(",") { it.name }})"
                found.putIfAbsent(signature, method)
            }
        }

        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            scan(current)
            for (parent in current.interfaces) scan(parent)
            current = current.superclass
        }

        return found.values.toList()
    }
}

public actual class EventBus internal actual constructor(
    exceptionHandler: ExceptionHandler,
    dispatchFilter: DispatchFilter?,
    dispatchStrategy: DispatchStrategy,
    binding: BindingStrategy
) : AbstractEventBus(exceptionHandler, dispatchFilter, dispatchStrategy, binding) {
    /**
     * [register], but binding through [invoker] rather than [Invokers.default],
     * and always reflectively even where a generated registration exists.
     *
     * Pass `weak = true` to let the bus hold [listener] weakly. The subscription
     * then stops delivering once nothing else references the listener, and is
     * pruned on the next post that notices. Do not rely on it for deterministic
     * teardown; [Subscription.cancel] is what does that.
     */
    @JvmOverloads
    public fun registerWith(
        listener: Any,
        invoker: Invoker = Invokers.default,
        weak: Boolean = false
    ): Subscription = reflectivelyRegister(listener, invoker, weak)

    /**
     * Subscribes the static [EventSubscriber] functions declared on [type].
     *
     * For Kotlin `object` and `companion object` declarations, pass the instance
     * to [register] instead; their functions are instance functions.
     */
    @JvmOverloads
    public fun registerStatic(
        type: Class<*>,
        invoker: Invoker = Invokers.default
    ): Subscription {
        checkNotRegistered(type)

        return addAll(
            SubscriberMethods.get(type)
                .filter { Modifier.isStatic(it.modifiers) }
                .map { method -> registrationFor(method, type, invoker, weak = false) }
        )
    }

    /** [on] taking a [Class], for Java callers who have no `KClass`. */
    @JvmOverloads
    public fun <T : Any> on(
        type: Class<T>,
        priority: EventPriority = EventPriority.NORMAL,
        handler: EventHandler<T>
    ): Subscription = on(type.kotlin, priority, handler)
}

internal actual fun AbstractEventBus.registerReflectively(listener: Any, weak: Boolean): Subscription =
    reflectivelyRegister(listener, Invokers.of(binding), weak)

internal fun AbstractEventBus.reflectivelyRegister(
    listener: Any,
    invoker: Invoker,
    weak: Boolean
): Subscription {
    checkNotRegistered(listener)

    return addAll(
        SubscriberMethods.get(listener.javaClass).map { method ->
            registrationFor(method, listener, invoker, weak)
        }
    )
}

private fun AbstractEventBus.registrationFor(
    method: Method,
    target: Any,
    invoker: Invoker,
    weak: Boolean
): Registration {
    validate(method)

    val eventType = method.parameterTypes[0]
    return newRegistration(
        eventType = eventType.kotlin,
        priority = method.getAnnotation(EventSubscriber::class.java).priority,
        target = target,
        method = invoker.bind(method.declaringClass, eventType, method),
        weak = weak,
        descriptor = method
    )
}

private fun validate(method: Method) {
    val where = "${method.declaringClass.name}#${method.name}"

    require(method.parameterCount == 1) {
        "$where is annotated with @EventSubscriber but takes ${method.parameterCount} parameters. " +
            "A subscriber takes exactly one, the event."
    }

    require(method.returnType == Void.TYPE) {
        "$where is annotated with @EventSubscriber but returns ${method.returnType.name}. " +
            "A subscriber returns nothing."
    }

    require(!method.parameterTypes[0].isPrimitive) {
        "$where subscribes to the primitive type ${method.parameterTypes[0].name}, which can never be posted."
    }

    val generic = method.genericParameterTypes[0]
    require(!(generic is ParameterizedType && generic.actualTypeArguments.any { it !is WildcardType })) {
        "$where subscribes to $generic, but type arguments are erased at runtime, so this would also " +
            "receive every other parameterisation of ${method.parameterTypes[0].name}. Subscribe to the " +
            "star-projected type and narrow it with an EventFilter, or check inside the subscriber."
    }
}
