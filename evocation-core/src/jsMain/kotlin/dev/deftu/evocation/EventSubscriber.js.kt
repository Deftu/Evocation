package dev.deftu.evocation

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public actual annotation class EventSubscriber(
    actual val priority: EventPriority
)
