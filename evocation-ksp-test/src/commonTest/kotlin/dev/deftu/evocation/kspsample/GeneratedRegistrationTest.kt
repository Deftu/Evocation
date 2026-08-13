package dev.deftu.evocation.kspsample

import dev.deftu.evocation.GeneratedSubscribers
import dev.deftu.evocation.bus
import dev.deftu.evocation.generated.installGeneratedSubscribers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises what the KSP processor generated, on every target.
 *
 * This module has no reflective path to fall back on, so passing on JS or Native
 * is only possible through the generated code.
 */
class GeneratedRegistrationTest {
    @Test
    fun generatedRegistrationDelivers() {
        val bus = bus()
        val listener = ChatLogger()
        bus.registerSubscribers(listener)

        bus.post(Message("hello"))

        assertEquals(listOf("first:hello", "hello"), listener.seen)
    }

    @Test
    fun generatedRegistrationHonoursPriority() {
        val bus = bus()
        val listener = ChatLogger()
        bus.registerSubscribers(listener)

        bus.post(Message("x"))

        assertEquals("first:x", listener.seen.first())
    }

    @Test
    fun generatedRegistrationCoversEveryEventType() {
        val bus = bus()
        val listener = ChatLogger()
        bus.registerSubscribers(listener)

        bus.post(Tick(3))

        assertEquals(listOf("tick:3"), listener.seen)
    }

    @Test
    fun generatedRegistrationWorksForObjects() {
        SingletonListener.seen.clear()
        val bus = bus()
        bus.registerSubscribers(SingletonListener)

        bus.post(Message("hello"))

        assertEquals(listOf("hello"), SingletonListener.seen)
    }

    @Test
    fun inheritedSubscribersAreCoveredByTheBase() {
        val bus = bus()
        val listener = InheritingListener()
        bus.registerSubscribers(listener)

        bus.post(Message("hello"))

        assertEquals(listOf("base:hello"), listener.seen)
    }

    @Test
    fun aSubclassCoversItsOwnAndItsInheritedSubscribers() {
        val bus = bus()
        val listener = ExtendingListener()
        bus.registerSubscribers(listener)

        bus.post(Message("hello"))
        bus.post(Tick(2))

        assertEquals(listOf("base:hello", "tick:2"), listener.seen)
    }

    @Test
    fun anOverrideRunsWithoutRepeatingTheAnnotation() {
        val bus = bus()
        val listener = OverridingListener()
        bus.registerSubscribers(listener)

        bus.post(Message("hello"))

        assertEquals(listOf("first", "override:hello"), listener.seen)
    }

    @Test
    fun commonRegisterUsesTheGeneratedBinding() {
        installGeneratedSubscribers()
        val bus = bus()
        val listener = ChatLogger()

        bus.register(listener)
        bus.post(Message("hello"))

        assertEquals(listOf("first:hello", "hello"), listener.seen)
    }

    @Test
    fun commonRegisterHonoursWeak() {
        installGeneratedSubscribers()
        val bus = bus()
        val listener = ChatLogger()

        bus.register(listener, weak = true)
        bus.post(Message("hello"))

        assertEquals(listOf("first:hello", "hello"), listener.seen)
    }

    @Test
    fun everyGeneratedTypeIsInstalled() {
        installGeneratedSubscribers()

        assertTrue(GeneratedSubscribers.isInstalled(ChatLogger::class))
        assertTrue(GeneratedSubscribers.isInstalled(ExtendingListener::class))
        assertTrue(GeneratedSubscribers.isInstalled(SingletonListener::class))
    }

    @Test
    fun generatedRegistrationReturnsACancellableHandle() {
        val bus = bus()
        val listener = ChatLogger()
        val subscription = bus.registerSubscribers(listener)

        subscription.cancel()
        bus.post(Message("hello"))

        assertEquals(emptyList(), listener.seen)
        assertEquals(0, bus.subscriptionCount)
    }
}
