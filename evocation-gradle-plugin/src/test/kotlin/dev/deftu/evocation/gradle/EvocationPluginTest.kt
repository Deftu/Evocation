package dev.deftu.evocation.gradle

import org.gradle.testkit.runner.GradleRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Builds a throwaway project against the plugin under test, because a plugin
 * that compiles tells you nothing about whether it wires anything up.
 */
class EvocationPluginTest {
    private fun project(build: String): File {
        val dir = createTempDir()
        File(dir, "settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                }
            }

            rootProject.name = "probe"
            """.trimIndent()
        )

        File(dir, "build.gradle.kts").writeText(build)
        return dir
    }

    private fun createTempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "evocation-plugin-${System.nanoTime()}").apply { mkdirs() }

    private fun run(dir: File, vararg args: String) =
        GradleRunner.create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withArguments(*args, "--stacktrace")
            .forwardOutput()
            .build()

    @Test
    fun addsTheProcessorToAJvmProject() {
        val dir = project(
            """
            plugins {
                id("org.jetbrains.kotlin.jvm")
                id("dev.deftu.evocation")
            }

            repositories { mavenCentral() }

            tasks.register("showKsp") {
                val names = configurations.named("ksp").map { configuration ->
                    configuration.dependencies.map { "${'$'}{it.group}:${'$'}{it.name}" }
                }

                doLast { println("ksp-dependencies=" + names.get()) }
            }
            """.trimIndent()
        )

        val result = run(dir, "showKsp")

        assertTrue(
            result.output.contains("ksp-dependencies=[dev.deftu:evocation-ksp]"),
            "the processor was not added to the ksp configuration:\n${result.output}"
        )
    }

    @Test
    fun appliesKspWithoutBeingAskedTo() {
        val dir = project(
            """
            plugins {
                id("org.jetbrains.kotlin.jvm")
                id("dev.deftu.evocation")
            }

            repositories { mavenCentral() }

            tasks.register("showPlugins") {
                val applied = plugins.hasPlugin("com.google.devtools.ksp")
                doLast { println("ksp-applied=" + applied) }
            }
            """.trimIndent()
        )

        val result = run(dir, "showPlugins")

        assertTrue(result.output.contains("ksp-applied=true"), result.output)
    }

    @Test
    fun addsTheProcessorToAMultiplatformProject() {
        val dir = project(
            """
            plugins {
                id("org.jetbrains.kotlin.multiplatform")
                id("dev.deftu.evocation")
            }

            repositories { mavenCentral() }

            tasks.register("showKsp") {
                val names = configurations.named("kspCommonMainMetadata").map { configuration ->
                    configuration.dependencies.map { "${'$'}{it.group}:${'$'}{it.name}" }
                }

                doLast { println("ksp-dependencies=" + names.get()) }
            }
            """.trimIndent()
        )

        val result = run(dir, "showKsp")

        assertTrue(
            result.output.contains("ksp-dependencies=[dev.deftu:evocation-ksp]"),
            "the processor was not added to the metadata configuration:\n${result.output}"
        )
    }

    @Test
    fun theProcessorVersionCanBeOverridden() {
        val dir = project(
            """
            plugins {
                id("org.jetbrains.kotlin.jvm")
                id("dev.deftu.evocation")
            }

            repositories { mavenCentral() }

            evocation {
                version.set("1.2.3")
                addCore.set(false)
            }

            tasks.register("showKsp") {
                val names = configurations.named("ksp").map { configuration ->
                    configuration.dependencies.map { "${'$'}{it.group}:${'$'}{it.name}:${'$'}{it.version}" }
                }

                doLast { println("ksp-dependencies=" + names.get()) }
            }
            """.trimIndent()
        )

        val result = run(dir, "showKsp")

        assertTrue(
            result.output.contains("dev.deftu:evocation-ksp:1.2.3"),
            "the configured version was not used:\n${result.output}"
        )
    }
}
