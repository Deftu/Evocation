package dev.deftu.evocation.internal

import dev.deftu.evocation.DispatchStrategy

/** Kotlin/Native is ahead-of-time compiled; dispatch always uses the loop. */
internal actual object FusedDispatchers {
    actual fun create(
        registrations: Array<Registration>,
        strategy: DispatchStrategy.Generated
    ): FusedDispatch? = null
}
