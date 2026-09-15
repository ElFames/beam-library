plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.androidLint)
    alias(libs.plugins.kotlinSerialization)
    id("maven-publish")
}

kotlin {

    // Usamos expect/actual class (BeamCrypto, BeamPrivateKey, SecureChannel) para poder
    // compartir el motor de transporte entre Android/Desktop/iOS sin filtrar tipos de
    // una plataforma a otra — todavía en Beta en el compilador de Kotlin.
    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    jvm()

    androidLibrary {
        namespace = "com.nubax.beam.library"
        compileSdk = 36
        minSdk = 24
    }

    // Cripto nativa de iOS: CryptoKit no es accesible directamente desde Kotlin/Native
    // (Swift-only, sin cabecera C/ObjC), así que `beam/native/ios/BeamCryptoKit.swift` se
    // compila aparte (swiftc -emit-objc-header-path, ver build.sh) y aquí solo se conecta
    // por cinterop contra esa cabecera generada — un target por triple de Apple porque cada
    // uno necesita su propia compilación de la librería estática.
    fun sdkPath(sdkName: String): String =
        providers.exec { commandLine("xcrun", "--sdk", sdkName, "--show-sdk-path") }
            .standardOutput.asText.get().trim()

    val iosNativeDir = layout.projectDirectory.dir("native/ios")
    val iosTargets = listOf(
        iosArm64() to Pair("iosArm64", "iphoneos"),
        iosSimulatorArm64() to Pair("iosSimulatorArm64", "iphonesimulator"),
    )

    iosTargets.forEach { (target, info) ->
        val (buildDirName, sdkName) = info
        val artifactDir = iosNativeDir.dir("build/$buildDirName")

        target.compilations.getByName("main") {
            cinterops.create("beamCryptoKit") {
                defFile(iosNativeDir.file("BeamCryptoKit.def"))
                compilerOpts("-I${artifactDir.asFile.absolutePath}")
            }
        }

        target.binaries.all {
            linkerOpts(
                "-L${artifactDir.asFile.absolutePath}",
                "-lBeamCryptoKit",
                "-L${sdkPath(sdkName)}/usr/lib/swift",
            )
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        // Compartido por Android y Desktop (ambos JVM): aquí vive el motor de
        // transporte/cripto real (MeshBeamConnection, BeamCrypto, SecureChannel,
        // BeamProtocol), que usa java.security/javax.crypto/java.net y por tanto
        // NO puede vivir en commonMain si algún día se añade un target no-JVM
        // (p. ej. ios*()) — ver PROJECT.md/plan de expect-actual.
        val jvmCommon by creating {
            dependsOn(getByName("commonMain"))
        }

        val iosMain by creating {
            dependsOn(getByName("commonMain"))
        }
        iosTargets.forEach { (target, _) ->
            getByName("${target.name}Main").dependsOn(iosMain)
            getByName("${target.name}Test").dependsOn(getByName("commonTest"))
        }

        androidMain {
            dependsOn(jvmCommon)
            dependencies {

            }
        }

        jvmMain {
            dependsOn(jvmCommon)
            dependencies {
                implementation(libs.kotlinx.coroutinesSwing)
            }
        }

    }

}