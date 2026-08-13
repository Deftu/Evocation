package dev.deftu.evocation

import kotlin.reflect.KClass

/**
 * Builds one subscription a piece at a time.
 *
 * ```kotlin
 * bus.filter<Message> { it.text == "Bar!" }
 *     .priority(EventPriority.HIGH)
 *     .on { it.text = "FooBar!" }
 * ```
 *
 * Nothing is registered until [on] is called. Because the builder knows the
 * event type, the predicates it takes are typed, where the flat
 * [AbstractEventBus.on] would need a cast.
 */
public class SubscriptionBuilder<T : Any> internal constructor(
    private val bus: AbstractEventBus,
    private val type: KClass<T>
) {
    private var priority: EventPriority = EventPriority.NORMAL
    private var filter: EventFilter<T>? = null

    public fun priority(priority: EventPriority): SubscriptionBuilder<T> = apply {
        this.priority = priority
    }

    /** Narrows what the subscriber sees. Several filters all have to pass. */
    public fun filter(filter: EventFilter<T>): SubscriptionBuilder<T> = apply {
        val existing = this.filter
        this.filter = if (existing == null) {
            filter
        } else {
            EventFilter { event -> existing.matches(event) && filter.matches(event) }
        }
    }

    /** Registers [handler] and returns its subscription. */
    public fun on(handler: EventHandler<T>): Subscription = bus.on(type, priority, filter, handler)
}

public fun <T : Any> AbstractEventBus.subscription(type: KClass<T>): SubscriptionBuilder<T> =
    SubscriptionBuilder(this, type)

public inline fun <reified T : Any> AbstractEventBus.subscription(): SubscriptionBuilder<T> =
    subscription(T::class)

/** Starts a subscription for [T] with its first filter. */
public inline fun <reified T : Any> AbstractEventBus.filter(
    filter: EventFilter<T>
): SubscriptionBuilder<T> = subscription(T::class).filter(filter)
