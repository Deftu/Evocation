package dev.deftu.evocation

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
public actual annotation class EventSubscriber(
    actual val priority: EventPriority
)
