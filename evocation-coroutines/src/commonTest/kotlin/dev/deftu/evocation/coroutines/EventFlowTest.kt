@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.deftu.evocation.coroutines

import dev.deftu.evocation.EventPriority
import dev.deftu.evocation.bus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class Message(val text: String)

class EventFlowTest {
    @Test
    fun collectsPostedEvents() = runTest {
        val bus = bus()
        val received = mutableListOf<String>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.events<Message>().collect { received.add(it.text) }
        }

        bus.post(Message("a"))
        bus.post(Message("b"))
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf("a", "b"), received)
    }

    @Test
    fun cancellingCollectionRemovesTheSubscription() = runTest {
        val bus = bus()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.events<Message>().collect { }
        }

        advanceUntilIdle()
        assertEquals(1, bus.subscriptionCount)

        job.cancel()
        advanceUntilIdle()

        assertEquals(0, bus.subscriptionCount)
    }

    @Test
    fun flowsHonourPriority() = runTest {
        val bus = bus()
        val order = mutableListOf<String>()

        val low = launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.events<Message>(EventPriority.LOWEST).collect { order.add("lowest") }
        }

        val high = launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.events<Message>(EventPriority.HIGHEST).collect { order.add("highest") }
        }

        advanceUntilIdle()
        bus.post(Message("x"))
        advanceUntilIdle()
        low.cancel()
        high.cancel()

        assertEquals(listOf("highest", "lowest"), order)
    }

    @Test
    fun suspendingSubscribersRun() = runTest {
        val bus = bus()
        val received = CompletableDeferred<String>()

        val scope = this
        bus.subscribeIn<Message>(scope) { event ->
            received.complete(event.text)
        }

        bus.post(Message("hello"))
        advanceUntilIdle()

        assertEquals("hello", received.await())
    }

    @Test
    fun subscriptionsBoundToAScopeEndWithIt() = runTest {
        val bus = bus()
        val scope = CoroutineScope(coroutineContext + Job())

        bus.subscribeIn<Message>(scope) { }
        assertEquals(1, bus.subscriptionCount)

        scope.cancel()
        advanceUntilIdle()

        assertEquals(0, bus.subscriptionCount)
    }
}
