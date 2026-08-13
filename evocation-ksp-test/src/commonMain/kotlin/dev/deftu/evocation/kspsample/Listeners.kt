package dev.deftu.evocation.kspsample

import dev.deftu.evocation.EventPriority
import dev.deftu.evocation.EventSubscriber

class Message(val text: String)

class Tick(val count: Int)

class ChatLogger {
    val seen: MutableList<String> = mutableListOf()

    @EventSubscriber
    fun onMessage(event: Message) {
        seen.add(event.text)
    }

    @EventSubscriber(EventPriority.HIGHEST)
    fun beforeEveryoneElse(event: Message) {
        seen.add("first:${event.text}")
    }

    @EventSubscriber
    fun onTick(event: Tick) {
        seen.add("tick:${event.count}")
    }
}

open class BaseListener {
    val seen: MutableList<String> = mutableListOf()

    @EventSubscriber
    open fun onMessage(event: Message) {
        seen.add("base:${event.text}")
    }
}

/** Declares nothing itself, so it must be covered by the base's function. */
class InheritingListener : BaseListener()

/** Adds its own, so its generated registration must cover both. */
class ExtendingListener : BaseListener() {
    @EventSubscriber
    fun onTick(event: Tick) {
        seen.add("tick:${event.count}")
    }
}

/** Overrides without repeating the annotation. */
class OverridingListener : BaseListener() {
    override fun onMessage(event: Message) {
        seen.add("override:${event.text}")
    }

    @EventSubscriber(EventPriority.HIGHEST)
    fun first(event: Message) {
        seen.add("first")
    }
}

object SingletonListener {
    val seen: MutableList<String> = mutableListOf()

    @EventSubscriber
    fun onMessage(event: Message) {
        seen.add(event.text)
    }
}
