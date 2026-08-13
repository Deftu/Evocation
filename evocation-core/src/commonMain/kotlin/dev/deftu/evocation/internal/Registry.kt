package dev.deftu.evocation.internal

import dev.deftu.evocation.DispatchStrategy
import kotlin.reflect.KClass

/**
 * What one concrete event type resolves to.
 *
 * The subscriber array is settled the moment this is built. The dispatcher is
 * not: generating one costs a class, so it waits until the type has been posted
 * [DispatchStrategy.Generated.fuseAfter] times. Every registration change builds
 * a new [Registry] and discards these counters, so a type whose subscribers
 * churn never reaches the threshold.
 */
internal class Resolution(
    val registrations: Array<Registration>,
    private val strategy: DispatchStrategy
) {
    private val dispatches = AtomicRef(0)
    private val state = AtomicRef<Any?>(null)

    /** The dispatcher, or null while the loop should be used. */
    fun dispatcher(): FusedDispatch? {
        when (val current = state.load()) {
            null -> Unit
            DECLINED -> return null
            else -> return current as FusedDispatch
        }

        val generated = strategy as? DispatchStrategy.Generated ?: return null

        val seen = dispatches.load()
        if (seen < generated.fuseAfter) {
            dispatches.compareAndSet(seen, seen + 1)
            return null
        }

        val built = FusedDispatchers.create(registrations, generated)
        state.compareAndSet(null, built ?: DECLINED)
        return state.load() as? FusedDispatch
    }

    /** Builds the dispatcher now, skipping the warm-up. For tests. */
    fun dispatcherNow(): FusedDispatch? {
        (strategy as? DispatchStrategy.Generated)?.let { dispatches.store(it.fuseAfter) }
        return dispatcher()
    }

    /** The dispatcher if one has been built already, without building one. */
    fun peek(): FusedDispatch? = state.load() as? FusedDispatch

    private companion object {
        val DECLINED = Any()
    }
}

/**
 * An immutable snapshot of every registration on a bus, plus a cache of what
 * each concrete event type resolves to.
 *
 * Resolution asks every registered type whether it accepts the event, rather
 * than walking the event's supertypes. Walking supertypes would need reflection
 * the common source set does not have, and the result is the same once cached:
 * the first post of a type pays for the scan, every later post is a map lookup.
 */
internal class Registry(
    val all: List<Registration>,
    private val strategy: DispatchStrategy
) {
    private val resolved = AtomicRef<Map<KClass<*>, Resolution>>(emptyMap())

    fun cached(type: KClass<*>): Resolution? = resolved.load()[type]

    fun resolve(event: Any): Resolution {
        val type = event::class
        resolved.load()[type]?.let { return it }

        // sortedByDescending is stable, so subscribers sharing a priority stay
        // in the order they were registered.
        val registrations = all
            .filter { it.eventType.isInstance(event) }
            .sortedByDescending { it.priority.ordinal }
            .toTypedArray()

        val computed = Resolution(registrations, strategy)

        while (true) {
            val current = resolved.load()
            current[type]?.let { return it }
            if (resolved.compareAndSet(current, current + (type to computed))) return computed
        }
    }

    fun with(registrations: List<Registration>): Registry = Registry(registrations, strategy)
}
