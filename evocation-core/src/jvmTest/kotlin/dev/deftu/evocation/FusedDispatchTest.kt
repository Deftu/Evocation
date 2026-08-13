package dev.deftu.evocation.reflection

import dev.deftu.evocation.DispatchStrategy
import dev.deftu.evocation.EventPriority
import dev.deftu.evocation.EventSubscriber
import dev.deftu.evocation.bus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirstListener(private val order: MutableList<String>) {
    @EventSubscriber(EventPriority.HIGHEST)
    fun onMessage(event: Message) {
        order.add("first")
    }
}

class SecondListener(private val order: MutableList<String>) {
    @EventSubscriber
    fun onMessage(event: Message) {
        order.add("second")
    }
}

class ThirdListener(private val order: MutableList<String>) {
    @EventSubscriber(EventPriority.LOWEST)
    fun onMessage(event: Message) {
        order.add("third")
    }
}

class ExplodingListener {
    @EventSubscriber
    fun onMessage(event: Message) {
        throw IllegalStateException("boom")
    }
}

private class NotPublicListener(val order: MutableList<String>) {
    @EventSubscriber
    fun onMessage(event: Message) {
        order.add("private")
    }
}

/**
 * Covers the generated dispatcher specifically.
 *
 * The buses here skip the warm-up so the generated code is what runs. Where it
 * matters whether fusing happened at all, the test asserts it, so a silent fall
 * back to the interpreted loop fails rather than passing quietly.
 */
class FusedDispatchTest {
    /** Skips the warm-up, so every test here exercises generated code. */
    private fun eagerBus() = bus { dispatch = DispatchStrategy.Generated(fuseAfter = 0) }

    @Test
    fun fusesSeveralDistinctListeners() {
        val order = mutableListOf<String>()
        val bus = eagerBus()
        bus.register(SecondListener(order))
        bus.register(FirstListener(order))
        bus.register(ThirdListener(order))

        assertTrue(bus.isFusedFor(Message("x")), "expected a generated dispatcher")

        bus.post(Message("x"))

        assertEquals(listOf("first", "second", "third"), order)
    }

    @Test
    fun fusedDispatchKeepsRegistrationOrderWithinAPriority() {
        val order = mutableListOf<String>()
        val bus = eagerBus()
        bus.register(SecondListener(order))
        bus.register(SecondListener(order))
        bus.register(FirstListener(order))

        assertTrue(bus.isFusedFor(Message("x")))

        bus.post(Message("x"))

        assertEquals(listOf("first", "second", "second"), order)
    }

    @Test
    fun fusedDispatchIsolatesAThrowingSubscriber() {
        val order = mutableListOf<String>()
        val caught = mutableListOf<Exception>()
        val bus = bus {
            dispatch = DispatchStrategy.Generated(fuseAfter = 0)
            exceptionHandler { caught.add(it) }
        }

        bus.register(FirstListener(order))
        bus.register(ExplodingListener())
        bus.register(ThirdListener(order))

        assertTrue(bus.isFusedFor(Message("x")))

        bus.post(Message("x"))

        assertEquals(listOf("first", "third"), order)
        assertEquals(1, caught.size)
        assertEquals("boom", caught.single().message)
    }

    @Test
    fun fusedDispatchSurvivesRepeatedPosts() {
        val order = mutableListOf<String>()
        val bus = eagerBus()
        bus.register(FirstListener(order))
        bus.register(ThirdListener(order))

        repeat(3) { bus.post(Message("x")) }

        assertEquals(listOf("first", "third", "first", "third", "first", "third"), order)
    }

    @Test
    fun registeringAgainRebuildsTheDispatcher() {
        val order = mutableListOf<String>()
        val bus = eagerBus()
        bus.register(FirstListener(order))
        bus.register(ThirdListener(order))
        bus.post(Message("a"))

        bus.register(SecondListener(order))
        order.clear()
        bus.post(Message("b"))

        assertEquals(listOf("first", "second", "third"), order)
    }

    @Test
    fun cancellingASubscriptionRebuildsTheDispatcher() {
        val order = mutableListOf<String>()
        val bus = eagerBus()
        bus.register(FirstListener(order))
        val second = bus.register(SecondListener(order))
        bus.register(ThirdListener(order))

        second.cancel()
        bus.post(Message("x"))

        assertEquals(listOf("first", "third"), order)
    }

    @Test
    fun fusedDispatchHandlesSubtypes() {
        val order = mutableListOf<String>()
        val bus = eagerBus()
        bus.register(FirstListener(order))
        bus.register(ThirdListener(order))

        bus.post(UrgentMessage("x"))

        assertEquals(listOf("first", "third"), order)
    }

    @Test
    fun doesNotFuseUntilATypeHasProvenStable() {
        val order = mutableListOf<String>()
        val bus = bus { dispatch = DispatchStrategy.Generated(fuseAfter = 5) }
        bus.register(FirstListener(order))
        bus.register(ThirdListener(order))

        repeat(4) { bus.post(Message("x")) }
        assertFalse(bus.isFusedYetFor(Message("x")), "fused before the threshold")

        repeat(2) { bus.post(Message("x")) }
        assertTrue(bus.isFusedYetFor(Message("x")), "never fused after the threshold")

        assertEquals(12, order.size)
    }

    @Test
    fun aChurningTypeNeverFuses() {
        val order = mutableListOf<String>()
        val bus = bus { dispatch = DispatchStrategy.Generated(fuseAfter = 5) }
        bus.register(FirstListener(order))

        repeat(20) {
            val subscription = bus.register(ThirdListener(order))
            bus.post(Message("x"))
            subscription.cancel()
        }

        assertFalse(bus.isFusedYetFor(Message("x")), "generated a dispatcher for a churning type")
    }

    @Test
    fun neverFuseTurnsGenerationOff() {
        val order = mutableListOf<String>()
        val bus = bus { dispatch = DispatchStrategy.Interpreted }
        bus.register(FirstListener(order))
        bus.register(ThirdListener(order))

        repeat(200) { bus.post(Message("x")) }

        assertFalse(bus.isFusedYetFor(Message("x")))
        assertEquals(400, order.size)
    }

    @Test
    fun declinesWhenABusUsesADispatchFilter() {
        val order = mutableListOf<String>()
        val bus = bus { stopDispatchWhen { false } }
        bus.register(FirstListener(order))
        bus.register(ThirdListener(order))

        bus.post(Message("x"))

        // The dispatcher may still be built, but dispatch must not use it or
        // the filter would be skipped.
        assertEquals(listOf("first", "third"), order)
    }

    @Test
    fun generatesForNonPublicListeners() {
        val order = mutableListOf<String>()
        val bus = eagerBus()
        bus.register(NotPublicListener(order))
        bus.register(FirstListener(order))

        // Not callable directly from another package, so it goes through the
        // SubscriberMethod its invoker produced, at its own call site.
        assertTrue(bus.isFusedFor(Message("x")))

        bus.post(Message("x"))

        assertEquals(listOf("first", "private"), order)
    }

    @Test
    fun generatesForLambdaSubscribers() {
        val order = mutableListOf<String>()
        val bus = eagerBus()
        bus.register(FirstListener(order))
        bus.on(Message::class, EventPriority.NORMAL) { order.add("lambda") }
        bus.register(ThirdListener(order))

        assertTrue(bus.isFusedFor(Message("x")), "a lambda should not stop a type generating")

        bus.post(Message("x"))

        assertEquals(listOf("first", "lambda", "third"), order)
    }

    @Test
    fun generatesForFilteredSubscribers() {
        val order = mutableListOf<String>()
        val bus = eagerBus()
        bus.register(FirstListener(order))
        bus.on(Message::class, EventPriority.LOWEST, { it.text == "keep" }) { order.add("filtered") }

        assertTrue(bus.isFusedFor(Message("keep")))

        bus.post(Message("keep"))
        bus.post(Message("drop"))

        assertEquals(listOf("first", "filtered", "first"), order)
    }

    @Test
    fun declinesWeakSubscribers() {
        val order = mutableListOf<String>()
        val bus = eagerBus()
        bus.register(FirstListener(order), weak = true)
        bus.register(ThirdListener(order))

        // A weak target is resolved per post and may be gone, and the bus has to
        // hear about that so it can prune, so the whole type stays on the loop.
        assertFalse(bus.isFusedFor(Message("x")), "a weak target must not be pinned in a generated field")

        bus.post(Message("x"))

        assertEquals(listOf("first", "third"), order)
    }

    @Test
    fun declinesASingleSubscriber() {
        val order = mutableListOf<String>()
        val bus = eagerBus()
        bus.register(FirstListener(order))

        assertFalse(bus.isFusedFor(Message("x")))

        bus.post(Message("x"))

        assertEquals(listOf("first"), order)
    }

    @Test
    fun declinesAboveTheSubscriberCap() {
        val order = mutableListOf<String>()
        val bus = bus { dispatch = DispatchStrategy.Generated(fuseAfter = 0, maxSubscribers = 2) }
        bus.register(FirstListener(order))
        bus.register(SecondListener(order))
        bus.register(ThirdListener(order))

        assertFalse(bus.isFusedFor(Message("x")))

        bus.post(Message("x"))

        assertEquals(listOf("first", "second", "third"), order)
    }

    @Test
    fun fusedDispatchCallsStaticSubscribers() {
        StaticHost.seen.clear()
        val order = mutableListOf<String>()
        val bus = eagerBus()
        bus.registerStatic(StaticHost::class.java)
        bus.register(FirstListener(order))

        bus.post(Message("hello"))

        assertEquals(listOf("hello"), StaticHost.seen)
        assertEquals(listOf("first"), order)
    }

    @Test
    fun fusedDispatchCallsInheritedMethods() {
        val order = mutableListOf<String>()
        val bus = eagerBus()
        val derived = DerivedListener()
        bus.register(derived)
        bus.register(FirstListener(order))

        assertTrue(bus.isFusedFor(Message("x")))

        bus.post(Message("x"))

        assertEquals(listOf("base:x"), derived.seen)
    }

    @Test
    fun fusedDispatchCallsTheOverrideNotTheBase() {
        val order = mutableListOf<String>()
        val bus = eagerBus()
        val overriding = OverridingListener()
        bus.register(overriding)
        bus.register(FirstListener(order))

        assertTrue(bus.isFusedFor(Message("x")))

        bus.post(Message("x"))

        assertEquals(listOf("override:x"), overriding.seen)
    }

    @Test
    fun fusedDispatchCallsInterfaceDefaultMethods() {
        val order = mutableListOf<String>()
        val bus = eagerBus()
        bus.register(InterfaceListener())
        bus.register(FirstListener(order))

        bus.post(Message("hello"))

        assertEquals(listOf("first"), order)
    }
}
