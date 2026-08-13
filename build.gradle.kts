plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.jmh) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.binary.compatibility.validator)
}

val projectGroup = extra["project.group"]?.toString()
    ?: throw IllegalArgumentException("The project group has not been set.")
val projectVersion = extra["project.version"]?.toString()
    ?: throw IllegalArgumentException("The project version has not been set.")

val unpublished = setOf("benchmarks", "evocation-ksp-test")

apiValidation {
    ignoredProjects += unpublished
}

subprojects {
    group = projectGroup
    version = projectVersion

    if (name in unpublished) return@subprojects

    apply(plugin = "maven-publish")
    apply(plugin = "org.jetbrains.dokka")

    val javadocJar = tasks.register<Jar>("javadocJar") {
        archiveClassifier.set("javadoc")
        from(tasks.named("dokkaHtml"))
    }

    configure<PublishingExtension> {
        publications.withType<MavenPublication>().configureEach {
            artifact(javadocJar)
        }

        repositories {
            if (project.hasProperty("deftu.publishing.username") && project.hasProperty("deftu.publishing.password")) {
                fun MavenArtifactRepository.applyCredentials() {
                    authentication.create<BasicAuthentication>("basic")
                    credentials {
                        username = property("deftu.publishing.username")?.toString()
                        password = property("deftu.publishing.password")?.toString()
                    }
                }

                maven {
                    name = "DeftuReleases"
                    url = uri("https://maven.deftu.dev/releases")
                    applyCredentials()
                }

                maven {
                    name = "DeftuSnapshots"
                    url = uri("https://maven.deftu.dev/snapshots")
                    applyCredentials()
                }
            }
        }
    }
}
