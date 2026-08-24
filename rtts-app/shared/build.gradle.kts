plugins {
    id("com.android.library")
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

kotlin {
    androidTarget {
        compilations.all {
            compilerOptions.configure {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
            }
        }
    }

    // iOS targets are declared so the project structure is ready, but they only actually
    // build on a macOS host -- Kotlin/Native's iOS backend requires the Xcode toolchain,
    // which does not exist on this Windows machine. Guarded so `:app:assembleDebug` etc.
    // keep working here; see README.md "iOS" section for what's left to do on a Mac.
    val isMacHost = System.getProperty("os.name").contains("Mac", ignoreCase = true)
    if (isMacHost) {
        listOf(
            iosX64(),
            iosArm64(),
            iosSimulatorArm64(),
        ).forEach { iosTarget ->
            iosTarget.binaries.framework {
                baseName = "shared"
                isStatic = true
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }
        androidMain.dependencies {
            implementation(files("libs/sherpa-onnx-1.13.4.aar"))
            api("androidx.room:room-runtime:2.6.1")
            api("androidx.room:room-ktx:2.6.1")
            implementation("androidx.core:core-ktx:1.13.1")
        }
        if (isMacHost) {
            getByName("iosMain").dependencies {
                // TODO(iOS): sherpa-onnx iOS build (.xcframework) + Kotlin/Native cinterop
                // binding against its C API. See README.md.
            }
        }
    }
}

android {
    namespace = "com.rtts.app.shared"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    packaging {
        resources.excludes.add("META-INF/*")
    }
}

dependencies {
    add("kspAndroid", "androidx.room:room-compiler:2.6.1")
}
