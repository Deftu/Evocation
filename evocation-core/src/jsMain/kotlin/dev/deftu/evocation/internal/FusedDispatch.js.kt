package dev.deftu.evocation.internal

import dev.deftu.evocation.DispatchStrategy

/** No code generation on JavaScript; dispatch always uses the loop. */
internal actual object FusedDispatchers {
    actual fun create(
        registrations: Array<Registration>,
        strategy: DispatchStrategy.Generated
    ): FusedDispatch? = null
}
