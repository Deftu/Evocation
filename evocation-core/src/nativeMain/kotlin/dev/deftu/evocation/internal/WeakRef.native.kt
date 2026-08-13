@file:OptIn(ExperimentalNativeApi::class)

package dev.deftu.evocation.internal

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference

public actual class WeakRef<T : Any> actual constructor(referent: T) {
    private val delegate = WeakReference(referent)

    public actual fun get(): T? = delegate.get()
}
