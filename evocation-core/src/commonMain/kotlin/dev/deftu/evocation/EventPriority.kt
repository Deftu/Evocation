package dev.deftu.evocation

/**
 * Controls the order subscribers run in. [HIGHEST] runs first.
 *
 * Subscribers sharing a priority run in registration order.
 */
public enum class EventPriority {
    LOWEST,
    LOW,
    NORMAL,
    HIGH,
    HIGHEST
}
