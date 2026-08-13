package dev.deftu.evocation.internal

import kotlin.concurrent.AtomicReference

internal actual class AtomicRef<T> actual constructor(initial: T) {
    private val delegate = AtomicReference(initial)

    actual fun load(): T = delegate.value

    actual fun store(value: T) {
        delegate.value = value
    }

    actual fun compareAndSet(expected: T, value: T): Boolean =
        delegate.compareAndSet(expected, value)
}
