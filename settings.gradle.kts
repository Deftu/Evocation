pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://jitpack.io/") {
            // Jitpack builds arbitrary sources on demand, so it is only trusted
            // for the one benchmark comparison that needs it.
            content {
                includeGroup("com.github.deamsy")
            }
        }
    }
}

rootProject.name =
    extra["project.name"]?.toString() ?: throw IllegalArgumentException("The project name has not been set.")

include(
    ":evocation-core",
    ":evocation-coroutines",
    ":evocation-ksp",
    ":evocation-gradle-plugin",
    ":evocation-ksp-test",
    ":benchmarks"
)
