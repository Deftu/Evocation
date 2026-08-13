package dev.deftu.evocation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private interface Notification

private open class Message(val text: String) : Notification

private class UrgentMessage(text: String) : Message(text)

private class Unrelated

class EventBusTest {
    @Test
    fun deliversToLambdaSubscribers() {
        val bus = bus()
        val seen = mutableListOf<String>()
        bus.on<Message> { seen.add(it.text) }

        bus.post(Message("hello"))

        assertEquals(listOf("hello"), seen)
    }

    @Test
    fun runsHighestPriorityFirst() {
        val bus = bus()
        val order = mutableListOf<String>()
        bus.on<Message>(EventPriority.LOWEST) { order.add("lowest") }
        bus.on<Message>(EventPriority.HIGHEST) { order.add("highest") }
        bus.on<Message>(EventPriority.NORMAL) { order.add("normal") }

        bus.post(Message("x"))

        assertEquals(listOf("highest", "normal", "lowest"), order)
    }

    @Test
    fun keepsRegistrationOrderWithinAPriority() {
        val bus = bus()
        val order = mutableListOf<String>()
        bus.on<Message> { order.add("a") }
        bus.on<Message>(EventPriority.HIGHEST) { order.add("high") }
        bus.on<Message> { order.add("b") }
        bus.on<Message> { order.add("c") }

        bus.post(Message("x"))

        assertEquals(listOf("high", "a", "b", "c"), order)
    }

    @Test
    fun orderIsStableAcrossPosts() {
        val bus = bus()
        val order = mutableListOf<String>()
        bus.on<Message>(EventPriority.LOW) { order.add("low") }
        bus.on<Message>(EventPriority.HIGH) { order.add("high") }

        repeat(3) { bus.post(Message("x")) }

        assertEquals(listOf("high", "low", "high", "low", "high", "low"), order)
    }

    @Test
    fun deliversToSupertypeSubscribers() {
        val bus = bus()
        val seen = mutableListOf<String>()
        bus.on<Notification> { seen.add("notification") }
        bus.on<Message> { seen.add("message") }
        bus.on<UrgentMessage> { seen.add("urgent") }

        bus.post(UrgentMessage("x"))

        assertEquals(setOf("notification", "message", "urgent"), seen.toSet())
    }

    @Test
    fun doesNotDeliverToSubtypeSubscribers() {
        val bus = bus()
        val seen = mutableListOf<String>()
        bus.on<UrgentMessage> { seen.add("urgent") }

        bus.post(Message("x"))

        assertEquals(emptyList(), seen)
    }

    @Test
    fun subtypeDispatchStillRespectsPriority() {
        val bus = bus()
        val order = mutableListOf<String>()
        bus.on<Notification>(EventPriority.HIGHEST) { order.add("notification") }
        bus.on<UrgentMessage>(EventPriority.LOWEST) { order.add("urgent") }
        bus.on<Message>(EventPriority.NORMAL) { order.add("message") }

        bus.post(UrgentMessage("x"))

        assertEquals(listOf("notification", "message", "urgent"), order)
    }

    @Test
    fun reportsEventsThatReachedNobody() {
        val bus = bus()
        val dead = mutableListOf<Any>()
        bus.onDeadEvent { dead.add(it) }

        val event = Unrelated()
        bus.post(event)

        assertEquals(listOf<Any>(event), dead)
    }

    @Test
    fun doesNotReportDeadEventsWhenSomethingHandled() {
        val bus = bus()
        val dead = mutableListOf<Any>()
        bus.onDeadEvent { dead.add(it) }
        bus.on<Message> { }

        bus.post(Message("x"))

        assertEquals(emptyList(), dead)
    }

    @Test
    fun cancellingASubscriptionRemovesOnlyThatOne() {
        val bus = bus()
        val seen = mutableListOf<String>()
        bus.on<Message> { seen.add("first") }
        val second = bus.on<Message> { seen.add("second") }
        bus.on<Message> { seen.add("third") }

        second.cancel()
        bus.post(Message("x"))

        assertEquals(listOf("first", "third"), seen)
        assertFalse(second.isActive)
    }

    @Test
    fun subscriptionsClose() {
        val bus = bus()
        val seen = mutableListOf<String>()
        val subscription = bus.on<Message> { seen.add(it.text) }

        assertTrue(subscription.isActive)
        subscription.close()
        bus.post(Message("x"))

        assertEquals(emptyList(), seen)
    }

    @Test
    fun cancellingTwiceIsHarmless() {
        val bus = bus()
        val subscription = bus.on<Message> { }

        subscription.cancel()
        subscription.cancel()

        assertEquals(0, bus.subscriptionCount)
    }

    @Test
    fun unregisterRemovesByOwner() {
        val bus = bus()
        val seen = mutableListOf<String>()
        val handler = EventHandler<Message> { seen.add("kept") }
        bus.on(Message::class, handler)
        bus.on<Message> { seen.add("removed") }

        bus.unregister(handler)
        bus.post(Message("x"))

        assertEquals(listOf("removed"), seen)
    }

    @Test
    fun oneThrowingSubscriberDoesNotStopTheRest() {
        val caught = mutableListOf<Exception>()
        val bus = bus { exceptionHandler { caught.add(it) } }
        val seen = mutableListOf<String>()
        bus.on<Message>(EventPriority.HIGHEST) { throw IllegalStateException("boom") }
        bus.on<Message>(EventPriority.LOWEST) { seen.add("after") }

        bus.post(Message("x"))

        assertEquals(listOf("after"), seen)
        assertEquals(1, caught.size)
        assertEquals("boom", caught.single().message)
    }

    @Test
    fun lazyPostSkipsConstructionWhenNothingIsRegistered() {
        val bus = bus()
        var built = 0

        bus.post { built++; Message("x") }

        assertEquals(0, built)
    }

    @Test
    fun lazyPostBuildsWhenSomethingIsRegistered() {
        val bus = bus()
        val seen = mutableListOf<String>()
        bus.on<Message> { seen.add(it.text) }

        bus.post { Message("x") }

        assertEquals(listOf("x"), seen)
    }

    @Test
    fun countsSubscriptions() {
        val bus = bus()
        assertEquals(0, bus.subscriptionCount)

        val first = bus.on<Message> { }
        bus.on<Notification> { }
        assertEquals(2, bus.subscriptionCount)

        first.cancel()
        assertEquals(1, bus.subscriptionCount)
    }
}
