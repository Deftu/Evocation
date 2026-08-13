package dev.deftu.evocation

/**
 * A handle to something registered on a bus.
 *
 * Cancelling is idempotent. Being [AutoCloseable] means a subscription works
 * with `use { }` in Kotlin and try-with-resources in Java.
 */
public interface Subscription : AutoCloseable {
    public val isActive: Boolean

    public fun cancel()

    override fun close() {
        cancel()
    }
}

/** Folds several subscriptions into one that cancels all of them. */
public fun Collection<Subscription>.combined(): Subscription = when (size) {
    0 -> EmptySubscription
    1 -> first()
    else -> CompositeSubscription(toList())
}

/** A [Subscription] covering several registrations, cancelled as a unit. */
internal class CompositeSubscription(
    private val parts: List<Subscription>
) : Subscription {
    override val isActive: Boolean
        get() = parts.any { it.isActive }

    override fun cancel() {
        for (part in parts) part.cancel()
    }
}

internal object EmptySubscription : Subscription {
    override val isActive: Boolean = false
    override fun cancel() {}
}
