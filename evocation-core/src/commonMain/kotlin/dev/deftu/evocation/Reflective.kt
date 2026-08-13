package dev.deftu.evocation

/**
 * Subscribes [listener] by inspecting it at runtime.
 *
 * Only the JVM can. Everywhere else this is reached solely because no generated
 * binding existed, so it reports that.
 */
internal expect fun AbstractEventBus.registerReflectively(listener: Any, weak: Boolean): Subscription

internal fun noGeneratedRegistration(listener: Any): String =
    "No generated registration for ${listener::class.simpleName}, and this target cannot reflect. " +
        "Apply evocation-ksp, call installGeneratedSubscribers() once at startup, and make sure the " +
        "class declares its own @EventSubscriber functions rather than only inheriting them."
