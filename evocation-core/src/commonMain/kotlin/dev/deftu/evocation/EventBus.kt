package dev.deftu.evocation

/**
 * The bus you construct and pass around.
 *
 * Declared per platform so each one can add what only it can offer. The JVM
 * build gains `registerWith` and `registerStatic`, both of which reflect; the
 * other targets add nothing.
 */
public expect class EventBus internal constructor(
    exceptionHandler: ExceptionHandler,
    dispatchFilter: DispatchFilter?,
    dispatchStrategy: DispatchStrategy,
    binding: BindingStrategy
) : AbstractEventBus
