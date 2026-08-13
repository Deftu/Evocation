package dev.deftu.evocation.internal

import dev.deftu.evocation.DispatchStrategy
import dev.deftu.evocation.ExceptionHandler

/**
 * A generated dispatcher for one concrete event type.
 *
 * The loop in `post` calls every subscriber through the same
 * `SubscriberMethod.invoke` site. Past two distinct implementations that site
 * goes megamorphic and stops being inlined, which is what makes per-subscriber
 * cost climb. A generated dispatcher gives each subscriber its own call site,
 * seeing a single receiver type.
 */
internal interface FusedDispatch {
    fun dispatch(event: Any, handler: ExceptionHandler)
}

/**
 * Builds a dispatcher for the whole of [registrations], or returns null when the
 * platform or the subscribers rule it out and the caller should loop.
 *
 * Whole-type or nothing. Splitting a type between generated and interpreted
 * delivery costs more in plan-walking than the generated part saves.
 */
internal expect object FusedDispatchers {
    fun create(
        registrations: Array<Registration>,
        strategy: DispatchStrategy.Generated
    ): FusedDispatch?
}
