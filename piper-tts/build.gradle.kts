plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// ─── On-device offline TTS module (Piper) ────────────────────────────────────
// Wraps Piper (https://github.com/rhasspy/piper) neural TTS via JNI, running
// fully on-device against a local ONNX voice model (default: male voice
// "en_US-ryan-medium"). No network access at synthesis time.
//
// Piper depends on onnxruntime and piper-phonemize (espeak-ng based). Both
// are fetched at native-build time via CMake FetchContent, same rationale as
// the :llama-cpp module: keeps big upstream C/C++ sources out of git while
// still producing a fully self-contained, offline .so bundled in the APK.

android {
    namespace = "com.aladdin.pipertts"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += listOf("-std=c++17")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // Provides prebuilt libonnxruntime.so per-ABI that CMakeLists.txt links
    // piper_bridge.cpp against (Piper's neural vocoder runs on onnxruntime).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
