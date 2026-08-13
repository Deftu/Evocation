<div align="center">

# `Evocation`

A Kotlin Multiplatform event bus built for thread-safety and dispatch speed.

<sup>Descended from [keventbus][keventbus] by [KevinPriv][kevin], though no code remains</sup>

</div>

## Modules

| Artifact | Targets | What it gives you |
| --- | --- | --- |
| `evocation-core` | jvm, js, linuxX64, mingwX64, macosX64, macosArm64, iosArm64, iosSimulatorArm64 | The bus itself |
| `evocation-coroutines` | same as core | `Flow` of events, suspending subscribers |
| `evocation-ksp` | jvm (KSP processor) | Generates registration for `@EventSubscriber` on non-JVM targets |
| `dev.deftu.evocation` | Gradle plugin | Applies KSP, adds the processor, and makes the generated code navigable |

Gradle resolves the right platform artifact automatically. Plain Maven users must
ask for the JVM one explicitly:

```xml
<artifactId>evocation-core-jvm</artifactId>
```

## Posting and subscribing

```kotlin
val bus = bus()

val subscription = bus.on<MessageReceived> { event ->
    println(event.text)
}

bus.post(MessageReceived("Hello, World!"))
subscription.cancel()
```

Subscriptions are `AutoCloseable`, so they work with `use { }` and, from Java,
try-with-resources.

## Annotated subscribers

```kotlin
class ChatLogger {
    @EventSubscriber
    fun onMessage(event: MessageReceived) {
        println(event.text)
    }

    @EventSubscriber(EventPriority.HIGHEST)
    fun beforeEveryoneElse(event: MessageReceived) {
    }
}

bus.register(ChatLogger())
```

`register` is common; the same call compiles and works on every target. What it
does underneath depends on what is available:

1. If `evocation-ksp` generated a binding for the listener's exact class, and
   `installGeneratedSubscribers()` has been called, it uses that. No reflection.
2. Otherwise, on the JVM, it reflects: superclasses and interface default methods
   included, with an override bound once, to the implementation that runs.
3. Otherwise it fails, naming the class and saying what to do about it. JS and
   Native have no runtime member enumeration to fall back on.

So a JVM-only project needs nothing but `register`, a multiplatform project adds
the processor and one startup call, and neither has to change its call sites.

The lookup in step 1 is by exact class. A subclass declaring no subscribers of
its own has nothing generated for it; on the JVM reflection covers that, and
elsewhere register the class that declares them.

Set the binding policy on the bus rather than at every call site. To override it
for one registration, or to supply a custom `Invoker`, the JVM has `registerWith`:

```kotlin
bus.registerWith(listener, ReflectionInvoker())
```

Static subscribers go through `registerStatic`, also JVM-only:

```kotlin
bus.registerStatic(MyStaticHost::class.java)
```

Kotlin `object` and `companion object` declarations are instances, so pass them
to `register` instead.

### Registering twice

`register`, `registerStatic` and the KSP-generated `registerSubscribers` all
reject a listener that is already registered:

```
IllegalStateException: ChatLogger@1b6d is already registered on this bus.
```

Double registration delivers every event twice while a single `unregister`
removes both copies, so it is far more often a bug than an intent. Check first
with `bus.isRegistered(listener)` if you need to.

`on` does not do this; separate handler instances are separate subscribers by
definition.

### From Java

```java
EventBus bus = new EventBusBuilder().build();

bus.register(new ChatLogger());
bus.on(MessageReceived.class, event -> System.out.println(event.getText()));
bus.post(new MessageReceived("Hello, World!"));
```

## Priority

`HIGHEST` runs first. Subscribers sharing a priority run in registration order.

## Hierarchical dispatch

A subscriber receives its event type **and every subtype**:

```kotlin
interface Notification
open class Message(val text: String) : Notification
class UrgentMessage(text: String) : Message(text)

bus.on<Notification> { }   // sees all three
bus.on<UrgentMessage> { }  // sees only UrgentMessage

bus.post(UrgentMessage("!"))
```

Priority is applied across the whole matched set, not per type.

The first post of a concrete type works out which subscribers match and caches
it; later posts of that type are a map lookup.

## Generic events

A class carries no type arguments at runtime, so `Boxed<String>` and `Boxed<Int>`
are the same subscription. Subscribing to one of them would quietly receive the
other, so the bus rejects it:

```kotlin
@EventSubscriber
fun onBoxed(event: Boxed<String>) { }  // fails at registration
```

Subscribe to the star projection and narrow it with a filter:

```kotlin
bus.on<Boxed<*>>(filter = { it.value is String }) { event ->
    println(event.value as String)
}
```

Filters are not limited to generics; any predicate works, and it runs before the
subscriber does. Annotated subscribers have nowhere to put one, so they take the
star projection and check inside.

## Building a subscription

For anything with more than one option, there is a builder:

```kotlin
bus.filter<Message> { it.text == "Bar!" }
    .priority(EventPriority.HIGH)
    .on { it.text = "FooBar!" }
```

`bus.subscription<Message>()` starts one without a filter. Chained filters all
have to pass. Nothing registers until `on`.

It is the same registration either way, so use whichever reads better: the flat
`on` for a single-option subscription, the builder once you are setting two or
three.

## Weak subscribers

```kotlin
bus.register(listener, weak = true)
```

The bus will not keep `listener` alive. Once nothing else references it, the
subscription stops delivering and is pruned on the next post that notices.

Collection timing is the runtime's business, so treat this as a leak guard, not
as teardown. `Subscription.cancel()` is what deterministically unsubscribes.

## Dead events

```kotlin
bus.onDeadEvent { event ->
    logger.warn("nothing handled {}", event)
}
```

Fires when a posted event matched no subscriber at all. Worth wiring up early:
it turns "my handler silently never ran" into something you can see.

## Interceptors

```kotlin
bus.addInterceptor(object : DispatchInterceptor {
    override fun beforePost(event: Any) = tracer.start(event::class.simpleName)
    override fun afterPost(event: Any) = tracer.stop()
})
```

Wraps every post, including ones that reach no subscriber. Both functions
default to doing nothing, so override only the side you need.

`afterPost` runs even when a subscriber threw, and interceptors unwind in
reverse order, so a pair of them nests. An interceptor that throws is reported to
the bus's `ExceptionHandler` and does not stop the post.

## Cancellation

**The bus ships no `Cancellable` type, on purpose.** Define your own:

```kotlin
interface Cancellable {
    var cancelled: Boolean
}

class Attack(val damage: Int) : Cancellable {
    override var cancelled = false
}
```

and tell the bus what it means:

```kotlin
val bus = bus {
    stopDispatchWhen { event -> event is Cancellable && event.cancelled }
}
```

### Why this shape

Leaving cancellation entirely to users is *almost* right. The part you cannot
write yourself is the short-circuit: a subscriber that checks `cancelled` and
returns early has still been invoked, and so has every subscriber after it. Only
the bus can stop its own dispatch loop.

So the bus provides the one thing only it can, stopping, and none of the
policy. It has no opinion on what "cancelled" means, whether a cancelled event
should still reach lower-priority subscribers, or what your flag is called.

`stopDispatchWhen` is unset by default, so a bus that never opts in behaves
exactly as if the feature did not exist. Setting the flag on an event does
nothing on its own; cancellation happens only because a bus was configured to
apply it. Types that never implement your interface are untouched.

`evocation-core/src/commonTest/kotlin/dev/deftu/evocation/CancellationTest.kt`
demonstrates each of these properties as a runnable test.

## Coroutines

```kotlin
bus.events<MessageReceived>()
    .collect { println(it.text) }

bus.subscribeIn<MessageReceived>(scope) { event ->
    persist(event)
}
```

`events` cancels its subscription when collection stops. `subscribeIn` runs each
event in its own coroutine; handlers start in priority order but run
concurrently, so collect `events` instead when you need one-at-a-time ordering.

Because `post` does not suspend, `events` buffers. The default is unlimited,
which never drops but can grow if the collector falls behind; pass a `capacity`
to bound it.

## Compile-time registration

`evocation-ksp` generates the registration the JVM would otherwise do
reflectively, which is what makes `@EventSubscriber` usable on JS and Native.

The Gradle plugin does the wiring:

```kotlin
plugins {
    id("dev.deftu.evocation")
}
```

That applies KSP, adds the processor and `evocation-core`, and registers the
directory KSP writes into as a source root, so the IDE indexes the generated code
and `Ctrl`+click on `registerSubscribers` opens it.

Configure it if you need to:

```kotlin
evocation {
    version.set("3.0.0")   // defaults to the plugin's own version
    addCore.set(false)     // if you already depend on evocation-core yourself
}
```

By hand instead, on a multiplatform project:

```kotlin
plugins {
    id("com.google.devtools.ksp")
}

dependencies {
    add("kspCommonMainMetadata", "dev.deftu:evocation-ksp:<version>")
}

kotlin.sourceSets.commonMain {
    kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    if (name != "kspCommonMainKotlinMetadata") dependsOn("kspCommonMainKotlinMetadata")
}
```

Call the installer once, before anything registers:

```kotlin
fun main() {
    installGeneratedSubscribers()
    bus.register(ChatLogger())
}
```

It is explicit because nothing on JS or Native runs a declaration's initializer
until something references it, so the bindings cannot install themselves.

For a class with subscriber functions the processor also emits a direct entry
point, if you would rather skip the lookup:

```kotlin
public fun AbstractEventBus.registerSubscribers(listener: ChatLogger, weak: Boolean = false): Subscription
```

Both paths see the same subscribers. The processor walks superclasses and
interfaces like reflection does, and follows an override back to the declaration
carrying the annotation, so overriding without repeating `@EventSubscriber` still
subscribes.

## Custom registration

Both paths above go through one public seam, which is also there for anything you
want to wire up yourself:

```kotlin
bus.registerHandler(
    eventType = MessageReceived::class,
    priority = EventPriority.NORMAL,
    target = myListener,
    method = { target, event -> (target as ChatLogger).onMessage(event as MessageReceived) }
)
```

## Invokers (JVM)

Mechanism, where `BindingStrategy` is policy. An `Invoker` turns a reflected
`Method` into something callable.

| Invoker | Binding |
| --- | --- |
| `LMFInvoker` | `LambdaMetafactory`; roughly a direct call |
| `ReflectionInvoker` | `Method.invoke` |
| `Invokers.of(strategy)` | What a bus's `BindingStrategy` asks for |

Each `LMFInvoker` binding spins a fresh class. That matters only where dispatch
is interpreted, since a generated dispatcher calls subscribers directly and
neither invoker is on that path.

`LMFInvoker` needs a private lookup to bind non-public methods. It prefers the
supported `MethodHandles.privateLookupIn` and only falls back to the
`sun.misc.Unsafe` `IMPL_LOOKUP` route when that is unavailable. That fallback
bypasses module access control, so choose `BindingStrategy.Reflective` if you
would rather it did not.

## Dispatch, and turning it off

The interpreted loop calls every subscriber through one shared interface call.
Past two distinct implementations that site goes megamorphic, the JIT stops
inlining through it, and per-subscriber cost climbs.

So on the JVM, an event type that has been posted enough times gets a generated
class calling each subscriber from its own call site:

```java
public void dispatch(Object event, ExceptionHandler handler) {
    try { t0.onMessage((Message) event); } catch (Exception e) { handler.handle(e); }
    try { t1.handle(event); }              catch (Exception e) { handler.handle(e); }
}
```

Every kind of subscriber gets a site: an annotated method is called directly, a
lambda through `EventHandler`, a non-public method through the `SubscriberMethod`
its invoker produced, and a filtered one behind its `EventFilter`. It is
whole-type or nothing, since splitting a type between generated and interpreted
delivery costs more in bookkeeping than the generated part saves.

Configure it from common code, naming no platform types:

```kotlin
val bus = bus {
    dispatch = DispatchStrategy.Generated(fuseAfter = 64, maxSubscribers = 32)
}

val quiet = bus { dispatch = DispatchStrategy.Interpreted }
```

`fuseAfter` is how many posts a type takes, with an unchanged subscriber set,
before generating for it. Generating costs a class, so waiting keeps that off
types whose subscribers churn. `maxSubscribers` you probably should not touch;
the useful value depends on the JIT rather than on anything you can see.

Generation is declined, and the loop used instead, when the type has fewer than
`minSubscribers` or more than `maxSubscribers`, when any subscriber is weak,
when the bus has a `stopDispatchWhen` filter, when the classes are not all
visible from one classloader, or when the platform cannot generate at all.
Behaviour is identical either way.

### Binding

Separately from dispatch, a reflectively discovered subscriber has to be made
callable. That is also policy, and also settable from common code:

```kotlin
val bus = bus { binding = BindingStrategy.Reflective }
```

`Fast` uses `LambdaMetafactory`, which defines a class per subscriber. Costs
about 5.5 ns against 8.2 at one subscriber, and nothing once a type generates,
since neither invoker is on that path.

### No runtime code generation at all

The two are separate because the constraints are: ASM dispatch needs a
classloader and `defineClass`, while `LambdaMetafactory` is a JDK facility using
hidden classes. Plenty of environments allow one and not the other. To rule out
both:

```kotlin
val bus = bus { noRuntimeCodeGeneration() }
```

That is pure `Method.invoke`, and works anywhere the JVM permits reflection.

### Supplying your own classloader

By default the generated class is defined into a fresh child of the listener's
own loader, one per class so it stays collectable. Wherever something else
already owns class loading, a mod loader or an application server, its author
knows the arrangement better than this library can guess:

```kotlin
ClassDefiners.default = ClassDefiner { name, bytes, neighbour ->
    myLoader.define(name, bytes)
}
```

Returning null, or throwing, declines generation for that type and falls back to
the loop.

## Thread safety

There is no thread-safety switch. Dispatch reads an immutable array through a
single atomic load, so posting takes no lock and copies nothing; registration
builds a new snapshot and swaps it in. Concurrent posting and registration are
always safe.

`ConcurrencyTest` holds it to that: concurrent posts deliver exactly once,
generated and interpreted; concurrent registrations and cancellations are never
lost; registering during dispatch never costs a stable subscriber an event; and
posting from inside a subscriber is safe. Each test releases its threads from a
single latch so the window is genuinely contended. JVM only, since JS has no
threads.

## Benchmarks

```
./gradlew :benchmarks:jmh
```

[kevin]: https://github.com/KevinPriv
[keventbus]: https://github.com/KevinPriv/keventbus
