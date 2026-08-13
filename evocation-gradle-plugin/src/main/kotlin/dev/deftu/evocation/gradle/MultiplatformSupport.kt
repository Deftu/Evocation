package dev.deftu.evocation.gradle

import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

/**
 * The multiplatform half of the plugin, kept apart so a JVM-only project never
 * loads a Kotlin Gradle Plugin multiplatform type it does not have.
 *
 * KSP runs once over common metadata, so every platform compilation has to wait
 * for it and the generated directory has to be added by hand. Removing that
 * boilerplate is most of why this plugin exists.
 */
internal object MultiplatformSupport {
    fun configure(project: Project, extension: EvocationExtension) {
        val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
        val generated = project.layout.buildDirectory.dir("generated/ksp/metadata/commonMain/kotlin")

        kotlin.sourceSets.named("commonMain") { sourceSet -> sourceSet.kotlin.srcDir(generated) }

        project.dependencies.addProvider(
            EvocationPlugin.KSP_METADATA_CONFIGURATION,
            extension.version.map { "${EvocationPlugin.GROUP}:${EvocationPlugin.PROCESSOR}:$it" }
        )

        project.dependencies.addProvider(
            "commonMainImplementation",
            extension.addCore.zip(extension.version) { add, version ->
                if (add) "${EvocationPlugin.GROUP}:${EvocationPlugin.CORE}:$version" else EvocationPlugin.EMPTY_DEPENDENCY
            }
        )

        project.tasks.withType(KotlinCompilationTask::class.java).configureEach { task ->
            if (task.name != EvocationPlugin.KSP_METADATA_TASK) {
                task.dependsOn(EvocationPlugin.KSP_METADATA_TASK)
            }
        }
    }
}
