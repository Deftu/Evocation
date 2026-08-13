package dev.deftu.evocation.internal

/**
 * The smallest atomic reference the bus needs, so the registry can be swapped
 * without a lock and without pulling in a dependency.
 */
internal expect class AtomicRef<T>(initial: T) {
    fun load(): T

    fun store(value: T)

    fun compareAndSet(expected: T, value: T): Boolean
}

/**
 * Replaces the value with [block] applied to it, retrying until the swap lands.
 *
 * [block] can run more than once, so it must not have side effects.
 */
internal inline fun <T> AtomicRef<T>.update(block: (T) -> T) {
    while (true) {
        val current = load()
        if (compareAndSet(current, block(current))) return
    }
}
