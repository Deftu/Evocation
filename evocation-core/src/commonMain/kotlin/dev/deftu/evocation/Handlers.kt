package dev.deftu.evocation

/**
 * A subscriber that closes over whatever it needs. Used by the lambda
 * subscription API.
 */
public fun interface EventHandler<in T : Any> {
    public fun handle(event: T)
}

/**
 * A subscriber that receives its target explicitly rather than capturing it.
 *
 * Keeping the target out of the closure is what makes weak subscriptions
 * possible: the bus holds the target weakly and still has something to invoke
 * once it resolves the reference.
 */
public fun interface SubscriberMethod {
    public fun invoke(target: Any, event: Any)
}

/**
 * Narrows a subscription beyond its event type.
 *
 * The bus matches subscribers by class, and a class carries no type arguments at
 * runtime, so `Event<String>` and `Event<Int>` are the same subscription. Where
 * that distinction matters, subscribe to the erased type and tell the bus how to
 * tell them apart here.
 */
public fun interface EventFilter<in T : Any> {
    public fun matches(event: T): Boolean
}

/** Receives exceptions thrown by subscribers. */
public fun interface ExceptionHandler {
    public fun handle(exception: Exception)
}

/** Receives events that reached no subscriber. */
public fun interface DeadEventHandler {
    public fun handle(event: Any)
}

/**
 * Decides whether dispatch of the current event should stop before the
 * remaining subscribers run.
 *
 * The bus deliberately ships no cancellation type of its own; this is the hook
 * you build one on. See the `Cancellation` section of the README.
 */
public fun interface DispatchFilter {
    public fun shouldStop(event: Any): Boolean
}

/**
 * Observes every post on a bus, including ones that reach no subscriber.
 *
 * Intended for logging, metrics and tracing. Both functions default to doing
 * nothing, so override only the side you need. An interceptor that throws is
 * reported to the bus's [ExceptionHandler] and does not stop the post.
 */
public interface DispatchInterceptor {
    public fun beforePost(event: Any) {}

    /** Runs even when a subscriber threw. Interceptors unwind in reverse order. */
    public fun afterPost(event: Any) {}
}
