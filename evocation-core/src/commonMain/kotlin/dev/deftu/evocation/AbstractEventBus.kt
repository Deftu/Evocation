package dev.deftu.evocation

import dev.deftu.evocation.internal.AtomicRef
import dev.deftu.evocation.internal.Registration
import dev.deftu.evocation.internal.Registry
import dev.deftu.evocation.internal.WeakRef
import dev.deftu.evocation.internal.update
import kotlin.reflect.KClass

/**
 * Everything the bus does that is the same on every platform.
 *
 * [EventBus] extends this per platform, which is how the JVM build gets
 * `registerWith` and `registerStatic` as real members rather than as static
 * helpers Java has to reach for.
 *
 * Dispatch reads an immutable array through one atomic load, so posting never
 * takes a lock and never copies. Registration pays for that by building a new
 * snapshot and swapping it in.
 */
public abstract class AbstractEventBus internal constructor(
    private val exceptionHandler: ExceptionHandler,
    private val dispatchFilter: DispatchFilter?,
    private val dispatchStrategy: DispatchStrategy,
    internal val binding: BindingStrategy
) {
    private val registry = AtomicRef(Registry(emptyList(), dispatchStrategy))
    private val deadEventHandlers = AtomicRef<List<DeadEventHandler>>(emptyList())
    private val interceptors = AtomicRef<List<DispatchInterceptor>>(emptyList())

    /** How many subscribers are currently registered, across all event types. */
    public val subscriptionCount: Int
        get() = registry.load().all.size

    /**
     * Delivers [event] to every subscriber whose event type is the event's class
     * or a supertype of it, highest priority first.
     *
     * A subscriber that throws does not stop the ones after it; the exception
     * goes to the bus's [ExceptionHandler].
     */
    public fun post(event: Any) {
        val interceptors = this.interceptors.load()
        if (interceptors.isEmpty()) {
            dispatch(event)
            return
        }

        for (index in interceptors.indices) {
            runInterceptor { interceptors[index].beforePost(event) }
        }

        try {
            dispatch(event)
        } finally {
            for (index in interceptors.indices.reversed()) {
                runInterceptor { interceptors[index].afterPost(event) }
            }
        }
    }

    /**
     * Registers an interceptor that sees every post on this bus, including posts
     * that reach no subscriber.
     */
    public fun addInterceptor(interceptor: DispatchInterceptor): Subscription {
        interceptors.update { it + interceptor }
        return object : Subscription {
            override val isActive: Boolean
                get() = interceptors.load().any { it === interceptor }

            override fun cancel() {
                interceptors.update { current -> current.filter { it !== interceptor } }
            }
        }
    }

    private inline fun runInterceptor(block: () -> Unit) {
        try {
            block()
        } catch (exception: Exception) {
            exceptionHandler.handle(exception)
        }
    }

    private fun dispatch(event: Any) {
        val resolution = registry.load().resolve(event)
        val handlers = resolution.registrations
        if (handlers.isEmpty()) {
            fireDeadEvent(event)
            return
        }

        val filter = dispatchFilter

        // Generated code cannot express stopping early, so a bus with a filter
        // keeps the loop.
        if (filter == null) {
            val dispatcher = resolution.dispatcher()
            if (dispatcher != null) {
                dispatcher.dispatch(event, exceptionHandler)
                return
            }
        }

        var sawCollected = false
        for (index in handlers.indices) {
            val registration = handlers[index]
            if (!deliver(registration, event)) {
                sawCollected = true
                continue
            }

            if (filter != null && filter.shouldStop(event)) break
        }

        if (sawCollected) pruneCollected()
    }

    /** Delivers unless the subscriber declined or its weak target is gone. */
    private fun deliver(registration: Registration, event: Any): Boolean {
        val target = registration.target() ?: return false
        if (!registration.accepts(event)) return true

        try {
            registration.deliver(target, event)
        } catch (exception: Exception) {
            exceptionHandler.handle(exception)
        }

        return true
    }

    /**
     * Builds the event only when the bus might deliver it. Exact once a type has
     * been posted at least once; before that it errs towards constructing,
     * because matching supertypes cannot be worked out without an instance.
     *
     * A member rather than an extension so it wins overload resolution against
     * [post]; an extension would lose and the lambda itself would be posted.
     */
    public inline fun <reified T : Any> post(supplier: () -> T) {
        if (mayHaveSubscribers(T::class)) post(supplier())
    }

    public fun <T : Any> on(type: KClass<T>, handler: EventHandler<T>): Subscription =
        on(type, EventPriority.NORMAL, null, handler)

    public fun <T : Any> on(
        type: KClass<T>,
        priority: EventPriority,
        handler: EventHandler<T>
    ): Subscription = on(type, priority, null, handler)

    /**
     * Subscribes [handler] to [type] and everything assignable to it.
     *
     * A [filter] narrows that further, and is the way to distinguish erased
     * generic types from one another.
     */
    @Suppress("UNCHECKED_CAST")
    public fun <T : Any> on(
        type: KClass<T>,
        priority: EventPriority,
        filter: EventFilter<T>?,
        handler: EventHandler<T>
    ): Subscription = add(
        Registration(
            eventType = type,
            priority = priority,
            owner = handler,
            strongTarget = handler,
            weakTarget = null,
            method = LambdaAdapter,
            filter = filter as EventFilter<Any>?
        )
    )

    /** Registers a handler for events that reached no subscriber at all. */
    public fun onDeadEvent(handler: DeadEventHandler): Subscription {
        deadEventHandlers.update { it + handler }
        return object : Subscription {
            override val isActive: Boolean
                get() = deadEventHandlers.load().any { it === handler }

            override fun cancel() {
                deadEventHandlers.update { current -> current.filter { it !== handler } }
            }
        }
    }

    public fun register(listener: Any): Subscription = register(listener, weak = false)

    /**
     * Subscribes every [EventSubscriber] function on [listener], wherever this
     * is running.
     *
     * Prefers what `evocation-ksp` generated for the listener's exact class, so a
     * project that applies the processor and calls `installGeneratedSubscribers()`
     * never reflects. Falls back to reflection on the JVM, and fails on targets
     * that have none.
     *
     * The lookup is by exact class. A subclass declaring no subscribers of its
     * own has nothing generated for it; on the JVM reflection covers that, and
     * elsewhere register the class that declares them instead.
     */
    public fun register(listener: Any, weak: Boolean): Subscription {
        GeneratedSubscribers.find(listener::class)?.let { return it.bind(this, listener, weak) }
        return registerReflectively(listener, weak)
    }

    public fun isRegistered(owner: Any): Boolean =
        registry.load().all.any { it.ownedBy(owner) }

    /**
     * Fails if [owner] is already registered.
     *
     * Registering the same listener twice delivers every event to it twice
     * while a single [unregister] removes both copies, so it is almost always a
     * mistake rather than an intent. Whole-object registration paths call this
     * first; [on] does not, because separate handler instances are separate
     * subscribers by definition.
     */
    public fun checkNotRegistered(owner: Any) {
        check(!isRegistered(owner)) {
            "$owner is already registered on this bus. Unregister it first, or hold on to the " +
                "Subscription from the original call if you meant to manage them separately."
        }
    }

    /**
     * Removes everything registered under [owner]: the listener object passed to
     * `register`, or the handler returned from [on].
     */
    public fun unregister(owner: Any) {
        registry.update { current ->
            val remaining = current.all.filter { !it.ownedBy(owner) }
            if (remaining.size == current.all.size) current else current.with(remaining)
        }
    }

    /**
     * Subscribes a handler the bus did not discover itself.
     *
     * Reflective `register` and the code `evocation-ksp` generates both end up
     * here, and so can anything you wire up by hand. [target] is passed back to
     * [method] on each delivery, and is what [unregister] matches on.
     */
    public fun registerHandler(
        eventType: KClass<*>,
        priority: EventPriority,
        target: Any,
        method: SubscriberMethod,
        weak: Boolean = false
    ): Subscription = add(newRegistration(eventType, priority, target, method, weak))

    internal fun add(registration: Registration): Subscription {
        registry.update { it.with(it.all + registration) }
        return RegistrationSubscription(registration)
    }

    internal fun addAll(registrations: List<Registration>): Subscription {
        if (registrations.isEmpty()) return EmptySubscription
        registry.update { it.with(it.all + registrations) }
        return CompositeSubscription(registrations.map { RegistrationSubscription(it) })
    }

    internal fun newRegistration(
        eventType: KClass<*>,
        priority: EventPriority,
        target: Any,
        method: SubscriberMethod,
        weak: Boolean,
        descriptor: Any? = null
    ): Registration = Registration(
        eventType = eventType,
        priority = priority,
        owner = if (weak) null else target,
        strongTarget = if (weak) null else target,
        weakTarget = if (weak) WeakRef(target) else null,
        method = method,
        descriptor = descriptor
    )

    /** Plans dispatch for [event]'s type, and reports whether anything fused. For tests. */
    internal fun isFusedFor(event: Any): Boolean =
        registry.load().resolve(event).dispatcherNow() != null

    /** Whether a plan has been built already, without building one. For tests. */
    internal fun isFusedYetFor(event: Any): Boolean =
        registry.load().resolve(event).peek() != null

    @PublishedApi
    internal fun mayHaveSubscribers(type: KClass<*>): Boolean {
        val current = registry.load()
        current.cached(type)?.let { return it.registrations.isNotEmpty() }

        // Nothing resolved for this type yet, and deciding whether a registered
        // type is a supertype of it needs an instance, which there is not one of
        // until the supplier runs.
        return current.all.isNotEmpty()
    }

    private fun fireDeadEvent(event: Any) {
        val handlers = deadEventHandlers.load()
        if (handlers.isEmpty()) return

        for (handler in handlers) {
            try {
                handler.handle(event)
            } catch (exception: Exception) {
                exceptionHandler.handle(exception)
            }
        }
    }

    private fun pruneCollected() {
        registry.update { current ->
            val remaining = current.all.filter { !it.isWeak || it.target() != null }
            if (remaining.size == current.all.size) current else current.with(remaining)
        }
    }

    private fun remove(registration: Registration) {
        registry.update { current ->
            if (current.all.none { it === registration }) current
            else current.with(current.all.filter { it !== registration })
        }
    }

    private inner class RegistrationSubscription(
        private val registration: Registration
    ) : Subscription {
        override val isActive: Boolean
            get() = registry.load().all.any { it === registration }

        override fun cancel() {
            remove(registration)
        }
    }

    /**
     * Every lambda subscription shares this one adapter, so the dispatch call
     * site sees a single implementation instead of one per subscriber.
     */
    private object LambdaAdapter : SubscriberMethod {
        override fun invoke(target: Any, event: Any) {
            @Suppress("UNCHECKED_CAST")
            (target as EventHandler<Any>).handle(event)
        }
    }
}

/** Subscribes to [T] and everything assignable to it. */
public inline fun <reified T : Any> AbstractEventBus.on(
    priority: EventPriority = EventPriority.NORMAL,
    filter: EventFilter<T>? = null,
    handler: EventHandler<T>
): Subscription = on(T::class, priority, filter, handler)
