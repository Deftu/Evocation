import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jmh)
}

dependencies {
    implementation(project(":evocation-core"))

    jmh(libs.guava)
    jmh(libs.deamsy.eventbus)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

jmh {
    warmupIterations.set(3)
    iterations.set(5)
    // Three forks, because a single one cannot separate a real change from JVM
    // to JVM variance, and several conclusions here turned on differences of
    // one or two nanoseconds.
    fork.set(3)
    // Dispatch is nanosecond scale, so a second still buys tens of millions of
    // operations per iteration. The JMH default of 10s each is wasted here.
    warmup.set("1s")
    timeOnIteration.set("1s")
    timeUnit.set("ns")
    benchmarkMode.set(listOf("avgt"))
    // Bytes allocated per post matters as much as nanoseconds for anything
    // running on a frame budget, where garbage turns into stutter.
    profilers.set(listOf("gc"))
}
