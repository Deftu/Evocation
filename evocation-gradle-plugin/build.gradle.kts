import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    `maven-publish`
}

/**
 * The probe projects run the Kotlin and KSP plugins in the same classloader as
 * the plugin under test, so TestKit needs their full runtime graph, not just the
 * API jars `compileOnly` puts on the compile classpath.
 */
val functionalTestClasspath: Configuration by configurations.creating

dependencies {
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.ksp.gradle.plugin)

    functionalTestClasspath(libs.kotlin.gradle.plugin)
    functionalTestClasspath(libs.ksp.gradle.plugin)

    testImplementation(libs.kotlin.test)
    testImplementation(gradleTestKit())
}

kotlin {
    explicitApi()

    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

gradlePlugin {
    plugins {
        create("evocation") {
            id = "dev.deftu.evocation"
            implementationClass = "dev.deftu.evocation.gradle.EvocationPlugin"
            displayName = "Evocation"
            description = "Wires the Evocation KSP processor and its generated sources into a Kotlin project."
        }
    }
}

/**
 * The plugin has to know which processor version to resolve, and it cannot ask
 * Gradle at execution time, so the build writes it in.
 */
val writeVersion by tasks.registering {
    val output = layout.buildDirectory.dir("generated/version")
    val value = project.version.toString()
    inputs.property("version", value)
    outputs.dir(output)

    doLast {
        val file = output.get().file("evocation.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=$value\n")
    }
}

sourceSets.main {
    resources.srcDir(writeVersion)
}

/**
 * TestKit injects only the plugin's own runtime classpath, so the Kotlin Gradle
 * Plugin has to be added or the probe projects cannot resolve it in the same
 * classloader the plugin runs in. Consumers get it from their own `plugins` block,
 * which is why it is `compileOnly` in the first place.
 */
tasks.pluginUnderTestMetadata {
    pluginClasspath.from(functionalTestClasspath)
}

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.pluginUnderTestMetadata)
}
