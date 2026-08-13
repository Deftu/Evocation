package dev.deftu.evocation.coroutines

import dev.deftu.evocation.AbstractEventBus
import dev.deftu.evocation.EventPriority
import dev.deftu.evocation.Subscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

/**
 * Every [type] posted to this bus, as a [Flow].
 *
 * The subscription lives as long as collection does and is cancelled when the
 * collector stops.
 *
 * `post` does not suspend, so events arriving faster than they are collected go
 * into a buffer of [capacity]. The default is unlimited, which never drops an
 * event but will grow without bound if the collector cannot keep up. Pass a
 * fixed capacity if you would rather drop than grow.
 */
public fun <T : Any> AbstractEventBus.events(
    type: KClass<T>,
    priority: EventPriority = EventPriority.NORMAL,
    capacity: Int = Channel.UNLIMITED
): Flow<T> = callbackFlow {
    val subscription = on(type, priority) { event -> trySend(event) }
    awaitClose { subscription.cancel() }
}.buffer(capacity, BufferOverflow.SUSPEND)

/** Every [T] posted to this bus, as a [Flow]. */
public inline fun <reified T : Any> AbstractEventBus.events(
    priority: EventPriority = EventPriority.NORMAL,
    capacity: Int = Channel.UNLIMITED
): Flow<T> = events(T::class, priority, capacity)

/**
 * Subscribes a suspending handler, running each event in a coroutine on [scope].
 *
 * Handlers start in priority order but run concurrently, so this does not
 * preserve the ordering guarantees a plain subscriber has. Collect [events] if
 * you need events handled one at a time.
 *
 * The subscription is cancelled when [scope] finishes.
 */
public fun <T : Any> AbstractEventBus.subscribeIn(
    scope: CoroutineScope,
    type: KClass<T>,
    priority: EventPriority = EventPriority.NORMAL,
    handler: suspend (T) -> Unit
): Subscription {
    val subscription = on(type, priority) { event ->
        scope.launch { handler(event) }
    }

    return subscription.boundTo(scope)
}

/** Subscribes a suspending handler for [T], running each event on [scope]. */
public inline fun <reified T : Any> AbstractEventBus.subscribeIn(
    scope: CoroutineScope,
    priority: EventPriority = EventPriority.NORMAL,
    noinline handler: suspend (T) -> Unit
): Subscription = subscribeIn(scope, T::class, priority, handler)

/** Cancels this subscription once [scope] completes. */
public fun Subscription.boundTo(scope: CoroutineScope): Subscription = apply {
    scope.coroutineContext.job.invokeOnCompletion { cancel() }
}
