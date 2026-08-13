package dev.deftu.evocation.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property
import java.util.Properties

/**
 * Everything a project needs to use `@EventSubscriber` without reflection.
 *
 * ```kotlin
 * plugins {
 *     id("dev.deftu.evocation")
 * }
 * ```
 *
 * Applies KSP, adds the processor, and registers the directory it writes into as
 * a source root so the IDE indexes the generated code and you can navigate to it.
 * That last part is the point: the generated registration is ordinary Kotlin you
 * can open and read, which nothing that generates bytecode can offer.
 */
public class EvocationPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val extension = target.extensions.create("evocation", EvocationExtension::class.java).apply {
            version.convention(pluginVersion())
            addCore.convention(true)
        }

        target.pluginManager.apply(KSP_PLUGIN)

        // Multiplatform configuration lives in its own class so its Kotlin
        // Gradle Plugin types are only loaded on a project that has them.
        target.pluginManager.withPlugin(MULTIPLATFORM_PLUGIN) {
            MultiplatformSupport.configure(target, extension)
        }

        target.pluginManager.withPlugin(JVM_PLUGIN) {
            configureJvm(target, extension)
        }

        target.afterEvaluate { project ->
            val kotlin = project.pluginManager.hasPlugin(MULTIPLATFORM_PLUGIN) ||
                project.pluginManager.hasPlugin(JVM_PLUGIN)

            if (!kotlin) {
                project.logger.warn(
                    "The Evocation plugin needs a Kotlin plugin to configure, and neither " +
                        "$MULTIPLATFORM_PLUGIN nor $JVM_PLUGIN is applied to ${project.path}."
                )
            }
        }
    }

    /**
     * KSP wires its own source roots on a plain JVM project.
     *
     * The dependencies are providers because this runs the moment the Kotlin
     * plugin is applied, which is before the `evocation { }` block has had a
     * chance to change anything.
     */
    private fun configureJvm(project: Project, extension: EvocationExtension) {
        project.dependencies.addProvider(
            KSP_CONFIGURATION,
            extension.version.map { "$GROUP:$PROCESSOR:$it" }
        )

        project.dependencies.addProvider(
            "implementation",
            extension.addCore.zip(extension.version) { add, version ->
                if (add) "$GROUP:$CORE:$version" else EMPTY_DEPENDENCY
            }
        )
    }

    private fun pluginVersion(): String {
        val stream = javaClass.classLoader.getResourceAsStream(VERSION_RESOURCE)
            ?: error("Evocation's Gradle plugin is missing $VERSION_RESOURCE, so it cannot tell which processor to resolve.")

        return stream.use { Properties().apply { load(it) }.getProperty("version") }
    }

    internal companion object {
        const val GROUP = "dev.deftu"
        const val CORE = "evocation-core"
        const val PROCESSOR = "evocation-ksp"

        const val KSP_PLUGIN = "com.google.devtools.ksp"
        const val MULTIPLATFORM_PLUGIN = "org.jetbrains.kotlin.multiplatform"
        const val JVM_PLUGIN = "org.jetbrains.kotlin.jvm"

        const val KSP_CONFIGURATION = "ksp"
        const val KSP_METADATA_CONFIGURATION = "kspCommonMainMetadata"
        const val KSP_METADATA_TASK = "kspCommonMainKotlinMetadata"

        const val VERSION_RESOURCE = "evocation.properties"

        /** Gradle needs a notation, so opting out means depending on nothing. */
        val EMPTY_DEPENDENCY: Any = emptyList<Any>()
    }
}

public abstract class EvocationExtension {
    /** Which Evocation to resolve. Defaults to the version of this plugin. */
    public abstract val version: Property<String>

    /** Whether to add `evocation-core` as a dependency. */
    public abstract val addCore: Property<Boolean>
}
