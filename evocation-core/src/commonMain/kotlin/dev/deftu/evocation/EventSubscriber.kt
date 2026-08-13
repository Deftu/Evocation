package dev.deftu.evocation

/**
 * Marks a function as an event subscriber. The function must take exactly one
 * parameter, the event, and return nothing.
 *
 * ```kotlin
 * @EventSubscriber
 * fun onMessage(event: MessageReceived) {
 *     println(event.text)
 * }
 * ```
 *
 * On the JVM these are found reflectively by `register`. Other platforms have no
 * runtime member enumeration, so there the processor in `evocation-ksp` generates
 * the equivalent registration at compile time.
 *
 * Declared per platform so retention can differ: only the JVM needs it at
 * runtime, and asking for runtime retention elsewhere warns at every use site.
 */
@Target(AnnotationTarget.FUNCTION)
public expect annotation class EventSubscriber(
    val priority: EventPriority = EventPriority.NORMAL
)
