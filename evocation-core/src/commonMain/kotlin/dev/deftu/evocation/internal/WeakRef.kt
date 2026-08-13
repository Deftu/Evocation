package dev.deftu.evocation.internal

/**
 * A reference that does not keep its referent alive.
 *
 * Every supported platform has one, under a different name, and none of them are
 * in the common standard library.
 */
public expect class WeakRef<T : Any>(referent: T) {
    public fun get(): T?
}
