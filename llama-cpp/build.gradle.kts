plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// ─── On-device offline LLM inference module ──────────────────────────────────
// Wraps llama.cpp (https://github.com/ggml-org/llama.cpp) via JNI so the
// Aladdin app can run a local GGUF model (e.g. gemma-3-1b-it.Q4_K_M.gguf)
// fully on-device, with NO Ollama server and NO internet connection required
// at runtime.
//
// llama.cpp's C/C++ source is NOT vendored into this repo (it's a large,
// fast-moving upstream project). Instead, CMake's FetchContent pulls a
// pinned release tag the first time the native build runs, then compiles it
// together with llama_bridge.cpp into libllama_bridge.so for each ABI.
// This keeps the git repo small while still producing a 100% offline APK —
// the APK that comes out of `./gradlew assembleRelease` bundles the compiled
// .so files, so end users never need network access or Ollama.

android {
    namespace = "com.aladdin.llamacpp"
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
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DLLAMA_CURL=OFF",
                    "-DGGML_LLAMAFILE=OFF"
                )
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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
