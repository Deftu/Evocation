package dev.deftu.evocation

import dev.deftu.evocation.internal.AtomicRef
import dev.deftu.evocation.internal.update
import kotlin.reflect.KClass

/** Subscribes a listener of one known type, without reflecting on it. */
public fun interface SubscriberBinder {
    public fun bind(bus: AbstractEventBus, listener: Any, weak: Boolean): Subscription
}

/**
 * The bindings `evocation-ksp` generated, keyed by listener class, so that
 * [AbstractEventBus.register] can find them.
 *
 * The processor emits an `installGeneratedSubscribers()` function that fills
 * this in. Call it once at startup. Nothing on JS or Native runs a declaration's
 * initializer until something references it, so this cannot populate itself.
 */
public object GeneratedSubscribers {
    private val binders = AtomicRef<Map<KClass<*>, SubscriberBinder>>(emptyMap())

    public fun install(type: KClass<*>, binder: SubscriberBinder) {
        binders.update { it + (type to binder) }
    }

    public fun isInstalled(type: KClass<*>): Boolean = binders.load().containsKey(type)

    internal fun find(type: KClass<*>): SubscriberBinder? = binders.load()[type]
}
