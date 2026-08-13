package dev.deftu.evocation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class Ping(val id: Int)

private class Unheard

private class Recording(private val log: MutableList<String>, private val name: String) : DispatchInterceptor {
    override fun beforePost(event: Any) {
        log.add("$name:before")
    }

    override fun afterPost(event: Any) {
        log.add("$name:after")
    }
}

class InterceptorTest {
    @Test
    fun wrapsDispatch() {
        val log = mutableListOf<String>()
        val bus = bus()
        bus.addInterceptor(Recording(log, "trace"))
        bus.on<Ping> { log.add("subscriber") }

        bus.post(Ping(1))

        assertEquals(listOf("trace:before", "subscriber", "trace:after"), log)
    }

    @Test
    fun unwindsInReverseOrder() {
        val log = mutableListOf<String>()
        val bus = bus()
        bus.addInterceptor(Recording(log, "outer"))
        bus.addInterceptor(Recording(log, "inner"))
        bus.on<Ping> { log.add("subscriber") }

        bus.post(Ping(1))

        assertEquals(
            listOf("outer:before", "inner:before", "subscriber", "inner:after", "outer:after"),
            log
        )
    }

    @Test
    fun seesEventsThatReachNobody() {
        val log = mutableListOf<String>()
        val bus = bus()
        bus.addInterceptor(Recording(log, "trace"))

        bus.post(Unheard())

        assertEquals(listOf("trace:before", "trace:after"), log)
    }

    @Test
    fun afterRunsWhenASubscriberThrows() {
        val log = mutableListOf<String>()
        val caught = mutableListOf<Exception>()
        val bus = bus { exceptionHandler { caught.add(it) } }
        bus.addInterceptor(Recording(log, "trace"))
        bus.on<Ping> { throw IllegalStateException("boom") }

        bus.post(Ping(1))

        assertEquals(listOf("trace:before", "trace:after"), log)
        assertEquals(1, caught.size)
    }

    @Test
    fun aThrowingInterceptorDoesNotStopThePost() {
        val caught = mutableListOf<Exception>()
        val seen = mutableListOf<Int>()
        val bus = bus { exceptionHandler { caught.add(it) } }
        bus.addInterceptor(object : DispatchInterceptor {
            override fun beforePost(event: Any) {
                throw IllegalStateException("interceptor broke")
            }
        })

        bus.on<Ping> { seen.add(it.id) }

        bus.post(Ping(7))

        assertEquals(listOf(7), seen)
        assertEquals(1, caught.size)
        assertEquals("interceptor broke", caught.single().message)
    }

    @Test
    fun canBeRemoved() {
        val log = mutableListOf<String>()
        val bus = bus()
        val subscription = bus.addInterceptor(Recording(log, "trace"))
        bus.on<Ping> { log.add("subscriber") }

        assertTrue(subscription.isActive)
        subscription.cancel()
        assertFalse(subscription.isActive)

        bus.post(Ping(1))

        assertEquals(listOf("subscriber"), log)
    }

    @Test
    fun seesTheEventInstance() {
        val ids = mutableListOf<Int>()
        val bus = bus()
        bus.addInterceptor(object : DispatchInterceptor {
            override fun beforePost(event: Any) {
                ids.add((event as Ping).id)
            }
        })

        bus.post(Ping(3))
        bus.post(Ping(9))

        assertEquals(listOf(3, 9), ids)
    }
}
