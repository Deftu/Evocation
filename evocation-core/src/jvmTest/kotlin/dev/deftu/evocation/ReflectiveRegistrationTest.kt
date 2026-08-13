package dev.deftu.evocation.reflection

import dev.deftu.evocation.EventPriority
import dev.deftu.evocation.EventSubscriber
import dev.deftu.evocation.bus
import dev.deftu.evocation.invokers.Invoker
import dev.deftu.evocation.invokers.LMFInvoker
import dev.deftu.evocation.invokers.ReflectionInvoker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

open class Message(val text: String)

class UrgentMessage(text: String) : Message(text)

open class BaseListener {
    val seen = mutableListOf<String>()

    @EventSubscriber
    open fun onMessage(event: Message) {
        seen.add("base:${event.text}")
    }
}

class DerivedListener : BaseListener()

class OverridingListener : BaseListener() {
    @EventSubscriber
    override fun onMessage(event: Message) {
        seen.add("override:${event.text}")
    }
}

interface ListeningInterface {
    val seen: MutableList<String>

    @EventSubscriber
    fun onMessage(event: Message) {
        seen.add("interface:${event.text}")
    }
}

class InterfaceListener : ListeningInterface {
    override val seen = mutableListOf<String>()
}

object ObjectListener {
    val seen = mutableListOf<String>()

    @EventSubscriber
    fun onMessage(event: Message) {
        seen.add(event.text)
    }
}

class StaticHost {
    companion object {
        val seen = mutableListOf<String>()

        @JvmStatic
        @EventSubscriber
        fun onMessage(event: Message) {
            seen.add(event.text)
        }
    }
}

class PriorityListener {
    val order = mutableListOf<String>()

    @EventSubscriber(EventPriority.LOWEST)
    fun low(event: Message) {
        order.add("lowest")
    }

    @EventSubscriber(EventPriority.HIGHEST)
    fun high(event: Message) {
        order.add("highest")
    }
}

class ThrowingListener {
    @EventSubscriber
    fun onMessage(event: Message) {
        throw IllegalStateException("boom")
    }
}

class TooManyParameters {
    @EventSubscriber
    fun onMessage(event: Message, extra: String) {
    }
}

class ReturnsSomething {
    @EventSubscriber
    fun onMessage(event: Message): String = ""
}

class Boxed<T>(val value: T)

class SubscribesToAConcreteTypeArgument {
    @EventSubscriber
    fun onBoxed(event: Boxed<String>) {
    }
}

class SubscribesToAStarProjection {
    val seen = mutableListOf<Any?>()

    @EventSubscriber
    fun onBoxed(event: Boxed<*>) {
        seen.add(event.value)
    }
}

class ReflectiveRegistrationTest {
    private fun invokers() = listOf(LMFInvoker(), ReflectionInvoker())

    private fun eachInvoker(block: (Invoker) -> Unit) {
        for (invoker in invokers()) block(invoker)
    }

    @Test
    fun registersAnnotatedMethods() = eachInvoker { invoker ->
        val bus = bus()
        val listener = BaseListener()
        bus.registerWith(listener, invoker)

        bus.post(Message("hello"))

        assertEquals(listOf("base:hello"), listener.seen)
    }

    @Test
    fun registersInheritedMethods() = eachInvoker { invoker ->
        val bus = bus()
        val listener = DerivedListener()
        bus.registerWith(listener, invoker)

        bus.post(Message("hello"))

        assertEquals(listOf("base:hello"), listener.seen)
    }

    @Test
    fun bindsAnOverrideOnlyOnce() = eachInvoker { invoker ->
        val bus = bus()
        val listener = OverridingListener()
        bus.registerWith(listener, invoker)

        bus.post(Message("hello"))

        assertEquals(listOf("override:hello"), listener.seen)
    }

    @Test
    fun registersInterfaceDefaultMethods() = eachInvoker { invoker ->
        val bus = bus()
        val listener = InterfaceListener()
        bus.registerWith(listener, invoker)

        bus.post(Message("hello"))

        assertEquals(listOf("interface:hello"), listener.seen)
    }

    @Test
    fun registersObjectDeclarations() = eachInvoker { invoker ->
        ObjectListener.seen.clear()
        val bus = bus()
        bus.registerWith(ObjectListener, invoker)

        bus.post(Message("hello"))

        assertEquals(listOf("hello"), ObjectListener.seen)
    }

    @Test
    fun registersStaticMethods() = eachInvoker { invoker ->
        StaticHost.seen.clear()
        val bus = bus()
        bus.registerStatic(StaticHost::class.java, invoker)

        bus.post(Message("hello"))

        assertEquals(listOf("hello"), StaticHost.seen)
    }

    @Test
    fun honoursAnnotationPriority() = eachInvoker { invoker ->
        val bus = bus()
        val listener = PriorityListener()
        bus.registerWith(listener, invoker)

        bus.post(Message("x"))

        assertEquals(listOf("highest", "lowest"), listener.order)
    }

    @Test
    fun deliversSubtypesToSupertypeSubscribers() = eachInvoker { invoker ->
        val bus = bus()
        val listener = BaseListener()
        bus.registerWith(listener, invoker)

        bus.post(UrgentMessage("urgent"))

        assertEquals(listOf("base:urgent"), listener.seen)
    }

    @Test
    fun handlerSeesWhatTheSubscriberThrew() = eachInvoker { invoker ->
        val caught = mutableListOf<Exception>()
        val bus = bus { exceptionHandler { caught.add(it) } }
        bus.registerWith(ThrowingListener(), invoker)

        bus.post(Message("x"))

        assertEquals(1, caught.size)
        assertTrue(caught.single() is IllegalStateException, "got ${caught.single()::class.java.name}")
        assertEquals("boom", caught.single().message)
    }

    @Test
    fun unregisterRemovesEverythingForAListener() = eachInvoker { invoker ->
        val bus = bus()
        val kept = BaseListener()
        val removed = BaseListener()
        bus.registerWith(kept, invoker)
        bus.registerWith(removed, invoker)

        bus.unregister(removed)
        bus.post(Message("x"))

        assertEquals(listOf("base:x"), kept.seen)
        assertEquals(emptyList(), removed.seen)
    }

    @Test
    fun registrationReturnsACancellableHandle() = eachInvoker { invoker ->
        val bus = bus()
        val listener = BaseListener()
        val subscription = bus.registerWith(listener, invoker)

        subscription.cancel()
        bus.post(Message("x"))

        assertEquals(emptyList(), listener.seen)
        assertEquals(0, bus.subscriptionCount)
    }

    @Test
    fun rejectsMethodsWithTheWrongParameterCount() {
        val bus = bus()

        val failure = assertFailsWith<IllegalArgumentException> {
            bus.register(TooManyParameters())
        }

        assertTrue(failure.message!!.contains("exactly one"), failure.message!!)
    }

    @Test
    fun rejectsMethodsThatReturnSomething() {
        val bus = bus()

        val failure = assertFailsWith<IllegalArgumentException> {
            bus.register(ReturnsSomething())
        }

        assertTrue(failure.message!!.contains("returns nothing"), failure.message!!)
    }

    @Test
    fun rejectsRegisteringTheSameListenerTwice() {
        val bus = bus()
        val listener = BaseListener()
        bus.register(listener)

        val failure = assertFailsWith<IllegalStateException> {
            bus.register(listener)
        }

        assertTrue(failure.message!!.contains("already registered"), failure.message!!)
        assertEquals(1, bus.subscriptionCount)
    }

    @Test
    fun allowsReregisteringAfterUnregister() {
        val bus = bus()
        val listener = BaseListener()
        bus.register(listener)
        bus.unregister(listener)
        bus.register(listener)

        bus.post(Message("x"))

        assertEquals(listOf("base:x"), listener.seen)
        assertEquals(1, bus.subscriptionCount)
    }

    @Test
    fun distinctInstancesOfTheSameClassAreNotDuplicates() {
        val bus = bus()
        val first = BaseListener()
        val second = BaseListener()

        bus.register(first)
        bus.register(second)

        assertEquals(2, bus.subscriptionCount)
    }

    @Test
    fun reportsWhetherAListenerIsRegistered() {
        val bus = bus()
        val listener = BaseListener()

        assertEquals(false, bus.isRegistered(listener))
        val subscription = bus.register(listener)
        assertTrue(bus.isRegistered(listener))

        subscription.cancel()
        assertEquals(false, bus.isRegistered(listener))
    }

    @Test
    fun rejectsSubscribingToAConcreteTypeArgument() {
        val bus = bus()

        val failure = assertFailsWith<IllegalArgumentException> {
            bus.register(SubscribesToAConcreteTypeArgument())
        }

        assertTrue(failure.message!!.contains("erased at runtime"), failure.message!!)
    }

    @Test
    fun acceptsAStarProjection() {
        val bus = bus()
        val listener = SubscribesToAStarProjection()
        bus.register(listener)

        bus.post(Boxed("a"))
        bus.post(Boxed(1))

        assertEquals(listOf<Any?>("a", 1), listener.seen)
    }

    @Test
    fun registeringSomethingWithNoSubscribersIsHarmless() {
        val bus = bus()

        val subscription = bus.register(Any())

        assertEquals(0, bus.subscriptionCount)
        subscription.cancel()
    }
}
