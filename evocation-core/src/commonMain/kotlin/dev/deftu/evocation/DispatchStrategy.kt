package dev.deftu.evocation

/**
 * Whether the bus may generate code to dispatch an event type, and within what
 * bounds.
 *
 * This is policy, not mechanism: it names no platform types and means the same
 * thing wherever it is written. Only the JVM can act on [Generated]; JS and
 * Native are ahead-of-time compiled and always interpret.
 */
public sealed interface DispatchStrategy {
    /**
     * Never generate code. Dispatch walks the subscriber array.
     *
     * Choose this where defining classes at runtime is unwelcome or refused: a
     * security manager, some application servers and mod loaders, Android.
     */
    public data object Interpreted : DispatchStrategy

    /**
     * Generate a class per event type, calling each subscriber from its own
     * call site so the JIT can inline through it.
     *
     * @param fuseAfter how many times a type must be posted, with an unchanged
     *   subscriber set, before generating for it. Generating costs a class, so
     *   waiting keeps that off types whose subscribers churn.
     * @param minSubscribers below which a generated class does not pay for
     *   itself.
     * @param maxSubscribers above which the generated method grows too large to
     *   be worth inlining. You probably do not need to change this; the useful
     *   value depends on the JIT rather than on anything you can see.
     */
    public data class Generated(
        val fuseAfter: Int = DEFAULT_FUSE_AFTER,
        val minSubscribers: Int = DEFAULT_MIN_SUBSCRIBERS,
        val maxSubscribers: Int = DEFAULT_MAX_SUBSCRIBERS
    ) : DispatchStrategy {
        public companion object {
            public const val DEFAULT_FUSE_AFTER: Int = 64
            public const val DEFAULT_MIN_SUBSCRIBERS: Int = 2
            public const val DEFAULT_MAX_SUBSCRIBERS: Int = 32
        }
    }
}

/**
 * How a subscriber discovered by reflection is turned into something callable.
 *
 * Only the JVM binds at runtime. Elsewhere the processor in `evocation-ksp` has
 * already produced a [SubscriberMethod], so this is ignored.
 */
public enum class BindingStrategy {
    /**
     * Bind as fast as the platform allows. On the JVM this defines a class per
     * subscriber through `LambdaMetafactory`.
     */
    Fast,

    /** Bind without defining any classes. Slower per call. */
    Reflective
}
