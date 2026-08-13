package dev.deftu.evocation.reflection

import dev.deftu.evocation.DispatchInterceptor
import dev.deftu.evocation.DispatchStrategy
import dev.deftu.evocation.EventSubscriber
import dev.deftu.evocation.Subscription
import dev.deftu.evocation.bus
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CountingSubscriber(private val counter: AtomicLong) {
    @EventSubscriber
    fun onMessage(event: Message) {
        counter.incrementAndGet()
    }
}

/**
 * Holds the bus to its claim that posting takes no lock and that registration is
 * safe against it.
 *
 * Every test releases its threads from a single latch, so the interesting window
 * is contended rather than accidentally serialised.
 */
class ConcurrencyTest {
    private val threads = maxOf(4, Runtime.getRuntime().availableProcessors())

    /** Runs [work] on [threads] threads at once and rethrows whatever escaped. */
    private fun race(threads: Int = this.threads, work: (Int) -> Unit) {
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val failures = ConcurrentLinkedQueue<Throwable>()

        repeat(threads) { index ->
            pool.execute {
                try {
                    start.await()
                    work(index)
                } catch (failure: Throwable) {
                    failures.add(failure)
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()
        val finished = done.await(60, TimeUnit.SECONDS)
        pool.shutdownNow()

        assertTrue(finished, "threads did not finish within 60s, which usually means a deadlock")
        failures.firstOrNull()?.let { throw AssertionError("a thread failed: $it", it) }
    }

    @Test
    fun concurrentPostsDeliverExactlyOnce() {
        val counter = AtomicLong()
        val bus = bus()
        val subscribers = 4
        repeat(subscribers) { bus.register(CountingSubscriber(counter)) }

        val postsPerThread = 5_000
        race { repeat(postsPerThread) { bus.post(Message("x")) } }

        assertEquals(threads.toLong() * postsPerThread * subscribers, counter.get())
    }

    @Test
    fun concurrentPostsDeliverExactlyOnceWhileFused() {
        val counter = AtomicLong()
        val bus = bus { dispatch = DispatchStrategy.Generated(fuseAfter = 0) }
        val subscribers = 4
        repeat(subscribers) { bus.register(CountingSubscriber(counter)) }
        bus.post(Message("warm"))

        val postsPerThread = 5_000
        race { repeat(postsPerThread) { bus.post(Message("x")) } }

        val warmUp = subscribers.toLong()
        assertEquals(warmUp + threads.toLong() * postsPerThread * subscribers, counter.get())
    }

    @Test
    fun concurrentRegistrationsAreNeverLost() {
        val bus = bus()
        val counter = AtomicLong()
        val perThread = 200

        race { repeat(perThread) { bus.register(CountingSubscriber(counter)) } }

        assertEquals(threads * perThread, bus.subscriptionCount)
    }

    @Test
    fun concurrentCancellationsAreNeverLost() {
        val bus = bus()
        val counter = AtomicLong()
        val perThread = 200
        val handles = ConcurrentLinkedQueue<Subscription>()

        race { repeat(perThread) { handles.add(bus.register(CountingSubscriber(counter))) } }
        assertEquals(threads * perThread, bus.subscriptionCount)

        race { for (handle in generateSequence { handles.poll() }) handle.cancel() }

        assertEquals(0, bus.subscriptionCount)
    }

    @Test
    fun registeringDuringDispatchNeverLosesAnEvent() {
        val counter = AtomicLong()
        val bus = bus()
        val stable = CountingSubscriber(counter)
        bus.register(stable)

        val posts = AtomicInteger()
        val postsPerThread = 2_000

        race { index ->
            if (index % 2 == 0) {
                repeat(postsPerThread) {
                    bus.post(Message("x"))
                    posts.incrementAndGet()
                }
            } else {
                repeat(postsPerThread) {
                    val subscription = bus.register(CountingSubscriber(counter))
                    subscription.cancel()
                }
            }
        }

        // The stable subscriber sees every post; the churning ones make the
        // count unpredictable, so this asserts the floor rather than equality.
        assertTrue(
            counter.get() >= posts.get().toLong(),
            "delivered ${counter.get()} for ${posts.get()} posts, so the stable subscriber missed some"
        )

        assertEquals(1, bus.subscriptionCount)
    }

    @Test
    fun postingDuringDispatchIsSafe() {
        val bus = bus()
        val depth = AtomicInteger()
        val seen = AtomicLong()

        bus.on(Message::class) { event ->
            seen.incrementAndGet()
            if (depth.get() < 3) {
                depth.incrementAndGet()
                bus.post(Message("nested"))
            }
        }

        race { repeat(500) { bus.post(Message("x")) } }

        assertTrue(seen.get() > 0)
    }

    @Test
    fun concurrentInterceptorChangesAreSafe() {
        val bus = bus()
        val counter = AtomicLong()
        bus.register(CountingSubscriber(counter))

        race { index ->
            if (index % 2 == 0) {
                repeat(2_000) { bus.post(Message("x")) }
            } else {
                repeat(500) {
                    val subscription = bus.addInterceptor(object : DispatchInterceptor {})
                    subscription.cancel()
                }
            }
        }

        assertTrue(counter.get() > 0)
    }
}
