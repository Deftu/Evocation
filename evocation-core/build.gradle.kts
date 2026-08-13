import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    explicitApi()

    compilerOptions {
        // expect/actual classes are Beta but the only way to give each platform
        // its own EventBus members. The warning is noise, not news.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }

        testRuns.named("test") {
            executionTask.configure {
                useJUnitPlatform()
            }
        }
    }

    js {
        browser()
        nodejs()
    }

    linuxX64()
    mingwX64()
    macosX64()
    macosArm64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        jvmMain.dependencies {
            // Used to generate a fused dispatcher per event type. Absence is
            // handled at runtime: dispatch falls back to the interpreted loop.
            implementation(libs.asm)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
