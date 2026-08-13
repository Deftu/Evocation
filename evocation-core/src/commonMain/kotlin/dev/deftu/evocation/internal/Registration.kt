package dev.deftu.evocation.internal

import dev.deftu.evocation.EventFilter
import dev.deftu.evocation.EventPriority
import dev.deftu.evocation.SubscriberMethod
import kotlin.reflect.KClass

/**
 * One subscriber on one event type.
 *
 * The target is held either strongly or weakly but never both, so dispatch is a
 * single branch rather than a virtual call into a subclass.
 */
internal class Registration(
    val eventType: KClass<*>,
    val priority: EventPriority,
    /**
     * Identity used by `unregister`. Null for weak registrations, which must not
     * hold their listener through this field or the reference would never be weak.
     */
    private val owner: Any?,
    private val strongTarget: Any?,
    private val weakTarget: WeakRef<Any>?,
    private val method: SubscriberMethod,
    /** Narrows what this subscriber accepts beyond its event type, if set. */
    val filter: EventFilter<Any>? = null,
    /**
     * How to call this subscriber directly, if the platform can. The JVM stores
     * the reflected `Method` here for [FusedDispatchers]. Untyped because common
     * code has no vocabulary for it and never reads it.
     */
    val descriptor: Any? = null
) {
    val isWeak: Boolean
        get() = weakTarget != null

    /** The bound call, for platforms that can inline it into generated code. */
    val subscriberMethod: SubscriberMethod
        get() = method

    /** The receiver to invoke, or null once a weak target has been collected. */
    fun target(): Any? = strongTarget ?: weakTarget?.get()

    fun ownedBy(candidate: Any): Boolean = owner === candidate || target() === candidate

    fun accepts(event: Any): Boolean = filter == null || filter.matches(event)

    fun deliver(target: Any, event: Any) {
        method.invoke(target, event)
    }
}
