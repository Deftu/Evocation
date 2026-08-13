package dev.deftu.evocation

import kotlin.test.Test
import kotlin.test.assertEquals

private class Boxed<T>(val value: T)

private class Named(val name: String)

/**
 * A class carries no type arguments at runtime, so `Boxed<String>` and
 * `Boxed<Int>` are one subscription. A filter is how you tell them apart.
 */
class EventFilterTest {
    @Test
    fun aFilterNarrowsWhatASubscriberSees() {
        val bus = bus()
        val strings = mutableListOf<String>()
        val ints = mutableListOf<Int>()

        bus.on<Boxed<*>>(filter = { it.value is String }) { strings.add(it.value as String) }
        bus.on<Boxed<*>>(filter = { it.value is Int }) { ints.add(it.value as Int) }

        bus.post(Boxed("a"))
        bus.post(Boxed(1))
        bus.post(Boxed("b"))

        assertEquals(listOf("a", "b"), strings)
        assertEquals(listOf(1), ints)
    }

    @Test
    fun withoutAFilterEveryParameterisationArrives() {
        val bus = bus()
        val seen = mutableListOf<Any?>()
        bus.on<Boxed<*>> { seen.add(it.value) }

        bus.post(Boxed("a"))
        bus.post(Boxed(1))

        assertEquals(listOf<Any?>("a", 1), seen)
    }

    @Test
    fun aDecliningFilterDoesNotStopOtherSubscribers() {
        val bus = bus()
        val order = mutableListOf<String>()

        bus.on<Named>(EventPriority.HIGHEST, filter = { false }) { order.add("declined") }
        bus.on<Named>(EventPriority.LOWEST) { order.add("ran") }

        bus.post(Named("x"))

        assertEquals(listOf("ran"), order)
    }

    @Test
    fun filtersRespectPriority() {
        val bus = bus()
        val order = mutableListOf<String>()

        bus.on<Named>(EventPriority.LOWEST, filter = { true }) { order.add("low") }
        bus.on<Named>(EventPriority.HIGHEST, filter = { true }) { order.add("high") }

        bus.post(Named("x"))

        assertEquals(listOf("high", "low"), order)
    }

    @Test
    fun aFilterSeesTheEventInstance() {
        val bus = bus()
        val seen = mutableListOf<String>()

        bus.on<Named>(filter = { it.name.startsWith("keep") }) { seen.add(it.name) }

        bus.post(Named("keep-1"))
        bus.post(Named("drop"))
        bus.post(Named("keep-2"))

        assertEquals(listOf("keep-1", "keep-2"), seen)
    }

    @Test
    fun theBuilderChainsFiltersAndPriority() {
        val bus = bus()
        val order = mutableListOf<String>()

        bus.filter<Named> { it.name.startsWith("keep") }
            .priority(EventPriority.HIGHEST)
            .on { order.add("first:${it.name}") }

        bus.subscription<Named>()
            .priority(EventPriority.LOWEST)
            .on { order.add("last:${it.name}") }

        bus.post(Named("keep-1"))
        bus.post(Named("drop"))

        assertEquals(listOf("first:keep-1", "last:keep-1", "last:drop"), order)
    }

    @Test
    fun severalBuilderFiltersAllHaveToPass() {
        val bus = bus()
        val seen = mutableListOf<String>()

        bus.filter<Named> { it.name.startsWith("keep") }
            .filter { it.name.endsWith("-2") }
            .on { seen.add(it.name) }

        bus.post(Named("keep-1"))
        bus.post(Named("keep-2"))
        bus.post(Named("drop-2"))

        assertEquals(listOf("keep-2"), seen)
    }

    @Test
    fun theBuilderRegistersNothingUntilOn() {
        val bus = bus()
        bus.filter<Named> { true }.priority(EventPriority.HIGH)

        assertEquals(0, bus.subscriptionCount)
    }

    @Test
    fun aFilteredSubscriberStillCountsAsRegistered() {
        val bus = bus()
        val subscription = bus.on<Named>(filter = { false }) { }

        assertEquals(1, bus.subscriptionCount)
        subscription.cancel()
        assertEquals(0, bus.subscriptionCount)
    }
}
