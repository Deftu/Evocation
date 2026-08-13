package dev.deftu.evocation.bench

import dev.deamsy.eventbus.impl.asm.ASMEventBus
import dev.deftu.evocation.EventBus
import dev.deftu.evocation.EventPriority
import dev.deftu.evocation.EventSubscriber
import dev.deftu.evocation.bus
import dev.deftu.evocation.invokers.LMFInvoker
import dev.deftu.evocation.invokers.ReflectionInvoker
import dev.deftu.evocation.on
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import java.util.concurrent.TimeUnit
import com.google.common.eventbus.Subscribe as GuavaSubscribe

class Payload(@JvmField val value: Int)

/** Shared sink so consuming the result is one field read everywhere. */
class Accumulator {
    @JvmField
    var sum: Long = 0
}

class EnhancedListener(private val accumulator: Accumulator) {
    @EventSubscriber
    fun onPayload(event: Payload) {
        accumulator.sum += event.value
    }
}

class GuavaListener(private val accumulator: Accumulator) {
    @GuavaSubscribe
    fun onPayload(event: Payload) {
        accumulator.sum += event.value
    }
}

class DirectListener(private val accumulator: Accumulator) {
    fun onPayload(event: Payload) {
        accumulator.sum += event.value
    }
}

/**
 * One `post` reaching N subscribers.
 *
 * Every subscriber on every bus adds into a shared accumulator and every
 * benchmark returns that one value, so the work outside dispatch is identical
 * across the buses being compared.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class DispatchBenchmark {
    @Param("1", "8")
    var subscribers: Int = 1

    private val lmfAccumulator = Accumulator()
    private val reflectionAccumulator = Accumulator()
    private val lambdaAccumulator = Accumulator()
    private val guavaAccumulator = Accumulator()
    private val deamsyAccumulator = Accumulator()
    private val mixedAccumulator = Accumulator()
    private val directAccumulator = Accumulator()

    private lateinit var event: Payload
    private lateinit var enhancedLmf: EventBus
    private lateinit var enhancedReflection: EventBus
    private lateinit var enhancedLambda: EventBus
    private lateinit var enhancedMixed: EventBus
    private lateinit var guava: com.google.common.eventbus.EventBus
    private lateinit var deamsy: ASMEventBus
    private lateinit var direct: Array<DirectListener>

    @Setup(Level.Trial)
    fun setup() {
        event = Payload(1)

        enhancedLmf = bus()
        enhancedReflection = bus()
        enhancedLambda = bus()
        enhancedMixed = bus()
        guava = com.google.common.eventbus.EventBus()
        deamsy = ASMEventBus()
        direct = Array(subscribers) { DirectListener(directAccumulator) }

        repeat(subscribers) {
            enhancedLmf.registerWith(EnhancedListener(lmfAccumulator), LMFInvoker())
            enhancedReflection.registerWith(EnhancedListener(reflectionAccumulator), ReflectionInvoker())
            guava.register(GuavaListener(guavaAccumulator))

            enhancedLambda.on<Payload> { lambdaAccumulator.sum += it.value }
            enhancedMixed.registerWith(EnhancedListener(mixedAccumulator), LMFInvoker())
            deamsy.registerLambda(Payload::class.java) { payload -> deamsyAccumulator.sum += payload.value }
        }

        // One lambda at the end, which under all-or-nothing fusing would have
        // disqualified the whole event type.
        enhancedMixed.on<Payload>(EventPriority.LOWEST) { mixedAccumulator.sum += it.value }
    }

    @Benchmark
    fun baselineDirectCall(): Long {
        for (listener in direct) listener.onPayload(event)
        return directAccumulator.sum
    }

    @Benchmark
    fun enhancedLmfInvoker(): Long {
        enhancedLmf.post(event)
        return lmfAccumulator.sum
    }

    @Benchmark
    fun enhancedReflectionInvoker(): Long {
        enhancedReflection.post(event)
        return reflectionAccumulator.sum
    }

    @Benchmark
    fun enhancedLambdaSubscriber(): Long {
        enhancedLambda.post(event)
        return lambdaAccumulator.sum
    }

    @Benchmark
    fun enhancedMixedWithOneLambda(): Long {
        enhancedMixed.post(event)
        return mixedAccumulator.sum
    }

    @Benchmark
    fun guavaEventBus(): Long {
        guava.post(event)
        return guavaAccumulator.sum
    }

    @Benchmark
    fun deamsyAsmEventBus(): Long {
        deamsy.post(event)
        return deamsyAccumulator.sum
    }
}
