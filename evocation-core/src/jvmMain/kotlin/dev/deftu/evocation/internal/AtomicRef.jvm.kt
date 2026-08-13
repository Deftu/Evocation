package dev.deftu.evocation.internal

import java.util.concurrent.atomic.AtomicReference

internal actual class AtomicRef<T> actual constructor(initial: T) {
    private val delegate = AtomicReference(initial)

    actual fun load(): T = delegate.get()

    actual fun store(value: T) {
        delegate.set(value)
    }

    actual fun compareAndSet(expected: T, value: T): Boolean =
        delegate.compareAndSet(expected, value)
}
