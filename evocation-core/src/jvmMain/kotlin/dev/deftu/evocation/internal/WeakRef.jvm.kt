package dev.deftu.evocation.internal

import java.lang.ref.WeakReference

public actual class WeakRef<T : Any> actual constructor(referent: T) {
    private val delegate = WeakReference(referent)

    public actual fun get(): T? = delegate.get()
}
