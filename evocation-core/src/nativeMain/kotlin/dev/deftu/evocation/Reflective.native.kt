package dev.deftu.evocation

internal actual fun AbstractEventBus.registerReflectively(listener: Any, weak: Boolean): Subscription =
    throw IllegalArgumentException(noGeneratedRegistration(listener))
