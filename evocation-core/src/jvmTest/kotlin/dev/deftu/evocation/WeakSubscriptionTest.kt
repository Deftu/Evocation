package dev.deftu.evocation.reflection

import dev.deftu.evocation.EventSubscriber
import dev.deftu.evocation.bus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CountingListener {
    var count = 0

    @EventSubscriber
    fun onMessage(event: Message) {
        count++
    }
}

class WeakSubscriptionTest {
    /**
     * Collection is at the JVM's discretion, so this nudges it rather than
     * assuming a single [System.gc] is enough.
     */
    private fun awaitCollection(attempts: Int = 200, check: () -> Boolean): Boolean {
        repeat(attempts) {
            System.gc()
            @Suppress("UNUSED_VARIABLE")
            val pressure = ByteArray(1 shl 20)
            if (check()) return true
            Thread.sleep(5)
        }

        return false
    }

    @Test
    fun weakSubscriptionsDeliverWhileTheListenerIsReachable() {
        val bus = bus()
        val listener = CountingListener()
        bus.register(listener, weak = true)

        bus.post(Message("a"))
        bus.post(Message("b"))

        assertEquals(2, listener.count)
    }

    @Test
    fun weakSubscriptionsStopOnceTheListenerIsCollected() {
        val bus = bus()
        var listener: CountingListener? = CountingListener()
        bus.register(listener!!, weak = true)

        bus.post(Message("a"))
        assertEquals(1, listener!!.count)
        assertEquals(1, bus.subscriptionCount)

        listener = null

        val pruned = awaitCollection {
            bus.post(Message("b"))
            bus.subscriptionCount == 0
        }

        assertTrue(pruned, "the weak subscription was never collected or pruned")
    }

    @Test
    fun strongSubscriptionsSurviveCollectionPressure() {
        val bus = bus()
        val listener = CountingListener()
        bus.register(listener)

        awaitCollection(attempts = 5) { false }
        bus.post(Message("a"))

        assertEquals(1, listener.count)
        assertEquals(1, bus.subscriptionCount)
    }

    @Test
    fun weakSubscriptionsCanStillBeCancelledOutright() {
        val bus = bus()
        val listener = CountingListener()
        val subscription = bus.register(listener, weak = true)

        subscription.cancel()
        bus.post(Message("a"))

        assertEquals(0, listener.count)
        assertEquals(0, bus.subscriptionCount)
    }
}

