package dev.deftu.evocation.internal

@JsName("WeakRef")
private external class JsWeakRef<T : Any>(target: T) {
    fun deref(): T?
}

/**
 * Backed by the ES2021 `WeakRef`. Collection is at the engine's discretion, so
 * a weak subscription may outlive its target for a while before it is pruned.
 */
public actual class WeakRef<T : Any> actual constructor(referent: T) {
    private val delegate = JsWeakRef(referent)

    public actual fun get(): T? = delegate.deref()
}
