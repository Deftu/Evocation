package dev.deftu.evocation.invokers

import java.lang.invoke.MethodHandles
import java.lang.reflect.Method

/**
 * How to obtain a [MethodHandles.Lookup] with private access to an arbitrary
 * class, which [LMFInvoker] needs to bind non-public subscriber methods.
 *
 * Ordered most to least supported. The first strategy that works on the running
 * JVM is cached and used from then on.
 */
internal enum class LookupStrategy {
    /**
     * `MethodHandles.privateLookupIn`, the supported JDK 9+ API. Reached
     * reflectively because this module still compiles against Java 8.
     *
     * Works for classes on the classpath and for modules opened to us, and fails
     * against an encapsulated one.
     */
    PRIVATE_LOOKUP_IN {
        override fun privateLookup(clazz: Class<*>): MethodHandles.Lookup {
            val privateLookupIn = MethodHandles::class.java.getMethod(
                "privateLookupIn",
                Class::class.java,
                MethodHandles.Lookup::class.java
            )

            return privateLookupIn.invoke(null, clazz, MethodHandles.lookup()) as MethodHandles.Lookup
        }
    },

    /**
     * Steals the trusted `IMPL_LOOKUP` through `sun.misc.Unsafe`.
     *
     * This bypasses module boundaries and access control outright, and is what
     * lets [LMFInvoker] bind methods the caller could not otherwise reach. Kept
     * only as a fallback for JVMs where [PRIVATE_LOOKUP_IN] is unavailable or
     * refused.
     */
    TRUSTED_IMPL_LOOKUP {
        override fun privateLookup(clazz: Class<*>): MethodHandles.Lookup {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafe = unsafeClass.getDeclaredField("theUnsafe")
            theUnsafe.isAccessible = true
            val unsafe = theUnsafe.get(null)

            val implLookup = MethodHandles.Lookup::class.java.getDeclaredField("IMPL_LOOKUP")
            val staticFieldBase = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java)
            val staticFieldOffset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java)
            val getObject = unsafeClass.getMethod("getObject", Any::class.java, java.lang.Long.TYPE)

            val lookup = getObject.invoke(
                unsafe,
                staticFieldBase.invoke(unsafe, implLookup),
                staticFieldOffset.invoke(unsafe, implLookup)
            ) as MethodHandles.Lookup

            return lookup.`in`(clazz)
        }
    },

    /** Java 8: mark a lookup trusted by writing its private `allowedModes` field. */
    ALLOWED_MODES {
        override fun privateLookup(clazz: Class<*>): MethodHandles.Lookup {
            val lookup = MethodHandles.lookup().`in`(clazz)
            val allowedModes = lookup.javaClass.getDeclaredField("allowedModes")
            allowedModes.isAccessible = true
            allowedModes.setInt(lookup, -1) // TRUSTED
            return lookup
        }
    };

    abstract fun privateLookup(clazz: Class<*>): MethodHandles.Lookup

    internal companion object {
        /**
         * Resolved once, on first use. `lazy` gives the synchronisation, so no
         * caller can observe a strategy that has not been proven to work.
         */
        val current: LookupStrategy by lazy { probe() }

        private fun probe(): LookupStrategy {
            for (strategy in entries) {
                try {
                    strategy.privateLookup(LookupStrategy::class.java)
                    return strategy
                } catch (ignored: Throwable) {
                    // Throwable rather than Exception: a JVM without sun.misc.Unsafe
                    // fails with NoClassDefFoundError, which must still fall through.
                }
            }

            throw UnsupportedOperationException(
                "No usable MethodHandles.Lookup strategy on this JVM. Use ReflectionInvoker instead of LMFInvoker."
            )
        }
    }
}

/** Best-effort `setAccessible`, false when the JVM refuses. */
internal fun makeAccessible(method: Method): Boolean = try {
    method.isAccessible = true
    true
} catch (ignored: Throwable) {
    false
}
