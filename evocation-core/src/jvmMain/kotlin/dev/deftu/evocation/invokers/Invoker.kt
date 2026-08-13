package dev.deftu.evocation.invokers

import dev.deftu.evocation.BindingStrategy
import dev.deftu.evocation.SubscriberMethod
import java.lang.invoke.LambdaMetafactory
import java.lang.invoke.MethodType
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Turns a reflected [Method] into something the bus can call.
 *
 * The result is unbound: it takes the receiver as an argument rather than
 * capturing it, so one binding serves every instance and a weak subscription can
 * hold its target separately.
 */
public interface Invoker {
    public fun bind(owner: Class<*>, eventType: Class<*>, method: Method): SubscriberMethod
}

/**
 * A single-argument subscriber, used for static methods that take no receiver.
 *
 * Public because [LambdaMetafactory] defines the class it spins in the target's
 * package, and that class has to be able to see the interface it implements.
 */
public fun interface StaticSubscriberMethod {
    public fun invoke(event: Any)
}

/**
 * Calls through `Method.invoke`.
 *
 * Slower per call than [LMFInvoker], but it binds anything the JVM will let us
 * reflect on and needs no privileged lookup.
 */
public class ReflectionInvoker : Invoker {
    override fun bind(owner: Class<*>, eventType: Class<*>, method: Method): SubscriberMethod {
        makeAccessible(method)
        val isStatic = Modifier.isStatic(method.modifiers)

        return SubscriberMethod { target, event ->
            try {
                if (isStatic) method.invoke(null, event) else method.invoke(target, event)
            } catch (exception: InvocationTargetException) {
                // Hand on what the subscriber actually threw rather than the
                // reflection wrapper, whose message is null.
                throw exception.cause ?: exception
            }
        }
    }
}

/**
 * Binds through [LambdaMetafactory], which produces a call roughly as fast as a
 * direct one.
 *
 * Each binding spins a fresh class, so several subscribers on one event type
 * leave the dispatch call site megamorphic and no longer inlined. That is why
 * this is not unconditionally faster than [ReflectionInvoker] as subscriber
 * counts grow, and why the fused dispatcher exists.
 */
public class LMFInvoker : Invoker {
    override fun bind(owner: Class<*>, eventType: Class<*>, method: Method): SubscriberMethod {
        val lookup = LookupStrategy.current.privateLookup(owner)
        val handle = lookup.unreflect(method)

        if (Modifier.isStatic(method.modifiers)) {
            val site = LambdaMetafactory.metafactory(
                lookup,
                "invoke",
                MethodType.methodType(StaticSubscriberMethod::class.java),
                MethodType.methodType(Void.TYPE, Any::class.java),
                handle,
                MethodType.methodType(Void.TYPE, eventType)
            )

            val static = site.target.invoke() as StaticSubscriberMethod
            return SubscriberMethod { _, event -> static.invoke(event) }
        }

        val site = LambdaMetafactory.metafactory(
            lookup,
            "invoke",
            MethodType.methodType(SubscriberMethod::class.java),
            MethodType.methodType(Void.TYPE, Any::class.java, Any::class.java),
            handle,
            MethodType.methodType(Void.TYPE, owner, eventType)
        )

        return site.target.invoke() as SubscriberMethod
    }
}

public object Invokers {
    /**
     * [LMFInvoker] where the JVM allows a privileged lookup, [ReflectionInvoker]
     * otherwise. Resolved once.
     */
    public val default: Invoker by lazy {
        try {
            LookupStrategy.current
            LMFInvoker()
        } catch (ignored: Throwable) {
            ReflectionInvoker()
        }
    }

    private val reflective: Invoker by lazy { ReflectionInvoker() }

    /** The invoker a bus's [BindingStrategy] asks for. */
    public fun of(strategy: BindingStrategy): Invoker = when (strategy) {
        BindingStrategy.Fast -> default
        BindingStrategy.Reflective -> reflective
    }
}
