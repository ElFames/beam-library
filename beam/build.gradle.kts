plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.androidLint)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {

    jvm()

    androidLibrary {
        namespace = "com.nubax.beam.library"
        compileSdk = 36
        minSdk = 24
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        androidMain {
            dependencies {

            }
        }

        jvmMain {
            dependencies {
                implementation(libs.kotlinx.coroutinesSwing)
            }
        }

    }

}