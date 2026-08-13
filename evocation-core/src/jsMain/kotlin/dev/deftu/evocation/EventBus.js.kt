package dev.deftu.evocation

/**
 * JavaScript has no runtime member enumeration, so nothing is added here.
 *
 * [AbstractEventBus.register] still works, but only for classes the processor in
 * `evocation-ksp` generated a binding for. Otherwise subscribe with
 * [AbstractEventBus.on].
 */
public actual class EventBus internal actual constructor(
    exceptionHandler: ExceptionHandler,
    dispatchFilter: DispatchFilter?,
    dispatchStrategy: DispatchStrategy,
    binding: BindingStrategy
) : AbstractEventBus(exceptionHandler, dispatchFilter, dispatchStrategy, binding)
