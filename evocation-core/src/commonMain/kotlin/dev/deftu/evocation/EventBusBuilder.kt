package dev.deftu.evocation

public class EventBusBuilder {
    /**
     * Runs when a subscriber throws. The default prints and lets dispatch carry
     * on to the remaining subscribers; re-throwing from here aborts the rest of
     * the current event.
     */
    public var exceptionHandler: ExceptionHandler =
        ExceptionHandler { println(it.stackTraceToString()) }

    /**
     * Consulted after each subscriber runs. Returning true stops the current
     * event reaching the subscribers that would have followed.
     *
     * Unset by default, which is what lets the bus define no cancellation type
     * of its own. Setting it also rules out generated dispatch, which cannot
     * express stopping early.
     */
    public var dispatchFilter: DispatchFilter? = null

    /** Whether the bus may generate code to dispatch, and within what bounds. */
    public var dispatch: DispatchStrategy = DispatchStrategy.Generated()

    /** How a reflectively discovered subscriber is made callable. */
    public var binding: BindingStrategy = BindingStrategy.Fast

    public fun exceptionHandler(handler: ExceptionHandler): EventBusBuilder = apply {
        exceptionHandler = handler
    }

    public fun stopDispatchWhen(filter: DispatchFilter): EventBusBuilder = apply {
        dispatchFilter = filter
    }

    public fun dispatch(strategy: DispatchStrategy): EventBusBuilder = apply {
        dispatch = strategy
    }

    public fun binding(strategy: BindingStrategy): EventBusBuilder = apply {
        binding = strategy
    }

    /**
     * Defines no classes at runtime, at either end: no generated dispatch and no
     * generated binding.
     */
    public fun noRuntimeCodeGeneration(): EventBusBuilder = apply {
        dispatch = DispatchStrategy.Interpreted
        binding = BindingStrategy.Reflective
    }

    public fun build(): EventBus = EventBus(exceptionHandler, dispatchFilter, dispatch, binding)
}

public fun bus(block: EventBusBuilder.() -> Unit = {}): EventBus =
    EventBusBuilder().apply(block).build()
