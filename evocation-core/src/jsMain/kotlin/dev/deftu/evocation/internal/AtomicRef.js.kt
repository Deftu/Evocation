package dev.deftu.evocation.internal

/**
 * JavaScript runs the bus on a single thread, so "atomic" costs nothing here.
 */
internal actual class AtomicRef<T> actual constructor(initial: T) {
    private var value: T = initial

    actual fun load(): T = value

    actual fun store(value: T) {
        this.value = value
    }

    actual fun compareAndSet(expected: T, value: T): Boolean {
        if (this.value !== expected) return false
        this.value = value
        return true
    }
}
