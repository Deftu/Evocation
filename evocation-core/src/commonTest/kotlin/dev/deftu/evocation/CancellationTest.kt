package dev.deftu.evocation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bus ships no cancellation type. This is what defining your own looks like,
 * and why the one hook it does provide is the part you cannot write yourself.
 */
private interface Cancellable {
    var cancelled: Boolean
}

private class Attack(val damage: Int) : Cancellable {
    override var cancelled: Boolean = false
}

private class Chat(val text: String)

class CancellationTest {
    private fun cancellableBus() = bus {
        stopDispatchWhen { event -> event is Cancellable && event.cancelled }
    }

    @Test
    fun cancellingStopsTheSubscribersThatWouldHaveFollowed() {
        val bus = cancellableBus()
        val ran = mutableListOf<String>()

        bus.on<Attack>(EventPriority.HIGHEST) { event ->
            ran.add("guard")
            event.cancelled = true
        }

        bus.on<Attack>(EventPriority.NORMAL) { ran.add("damage") }
        bus.on<Attack>(EventPriority.LOWEST) { ran.add("logging") }

        bus.post(Attack(damage = 10))

        // The point of the hook: the later subscribers never ran at all. A
        // subscriber checking `cancelled` itself cannot achieve this, because
        // only the bus can stop its own loop.
        assertEquals(listOf("guard"), ran)
    }

    @Test
    fun subscribersStillRunWhenNothingCancels() {
        val bus = cancellableBus()
        val ran = mutableListOf<String>()

        bus.on<Attack>(EventPriority.HIGHEST) { ran.add("guard") }
        bus.on<Attack>(EventPriority.LOWEST) { ran.add("damage") }

        bus.post(Attack(damage = 10))

        assertEquals(listOf("guard", "damage"), ran)
    }

    @Test
    fun theFilterOnlyTouchesTypesYouOptedIn() {
        val bus = cancellableBus()
        val ran = mutableListOf<String>()

        bus.on<Chat>(EventPriority.HIGHEST) { ran.add("first") }
        bus.on<Chat>(EventPriority.LOWEST) { ran.add("second") }

        bus.post(Chat("hello"))

        // Chat is not Cancellable, so the predicate is false and dispatch is
        // untouched. Types that never opt in behave as if the hook did not exist.
        assertEquals(listOf("first", "second"), ran)
    }

    @Test
    fun aBusWithNoFilterNeverStopsEarly() {
        val bus = bus()
        val ran = mutableListOf<String>()

        bus.on<Attack>(EventPriority.HIGHEST) { event ->
            ran.add("guard")
            event.cancelled = true
        }

        bus.on<Attack>(EventPriority.LOWEST) { ran.add("damage") }

        bus.post(Attack(damage = 10))

        // Setting the flag does nothing on its own. Cancellation is a policy the
        // bus only applies because the bus was configured to.
        assertEquals(listOf("guard", "damage"), ran)
    }

    @Test
    fun subscribersCanStillInspectTheFlagThemselves() {
        val bus = cancellableBus()
        var observed = false

        bus.on<Attack>(EventPriority.HIGHEST) { it.cancelled = true }
        bus.on<Attack>(EventPriority.LOWEST) { observed = true }

        val event = Attack(damage = 1)
        bus.post(event)

        assertTrue(event.cancelled)
        assertEquals(false, observed)
    }
}
