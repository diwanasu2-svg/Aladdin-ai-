import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("kotlin-parcelize")
    id("com.google.gms.google-services")    // Firebase / FCM
    id("com.google.firebase.crashlytics")   // Firebase Crashlytics build ID + mapping upload
}

// ─── Signing config from local.properties ────────────────────────────────────
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.aladdin.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aladdin.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 13   // Phase 13 (final)
        versionName = "13.0.0"
        multiDexEnabled = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testOptions {
            unitTests.isReturnDefaultValues = true
            unitTests.isIncludeAndroidResources = true
        }

        // Expose Gemini API key to BuildConfig (add GEMINI_API_KEY in local.properties)
        val localProps = Properties().also {
            val f = rootProject.file("local.properties")
            if (f.exists()) it.load(FileInputStream(f))
        }
        buildConfigField("String", "GEMINI_API_KEY",
            "\"${localProps.getProperty("GEMINI_API_KEY", "")}\"")

        // ── Phase 2: JNI libs filter ───────────────────────────────────────────
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    // ─── Signing ─────────────────────────────────────────────────────────────
    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                keyAlias      = keystoreProperties["keyAlias"] as String?      ?: ""
                keyPassword   = keystoreProperties["keyPassword"] as String?   ?: ""
                storeFile     = keystoreProperties["storeFile"]?.let { file(it as String) }
                storePassword = keystoreProperties["storePassword"] as String? ?: ""
            }
        }
        // Stable, committed debug keystore so every CI build (which otherwise
        // uses a fresh auto-generated ~/.android/debug.keystore per runner)
        // is always signed with the SAME key. Without this, each new debug
        // APK built on GitHub Actions gets a different random signature,
        // which makes installing an update over a previous build fail with
        // a generic "App not installed" error on the device.
        // NOTE: the Android Gradle Plugin already auto-creates a "debug"
        // signing config, so we must reconfigure it via getByName(), not
        // create() (which throws "SigningConfig ... already exists").
        getByName("debug") {
            storeFile     = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias      = "androiddebugkey"
            keyPassword   = "android"
        }
    }

    // ─── Build types ───────────────────────────────────────────────────────
    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // ─── AAB (Play Store) ─────────────────────────────────────────────────────
    bundle {
        language { enableSplit = true }
        density  { enableSplit = true }
        abi      { enableSplit = true }
    }

    // ─── Compile options ──────────────────────────────────────────────────────
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // ─── Packaging ────────────────────────────────────────────────────────
    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1"
            )
        }
        jniLibs {
            keepDebugSymbols += setOf("**/*.so")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = true
        warningsAsErrors = false
    }
}

dependencies {
    // ─── Internal modules ─────────────────────────────────────────────────────
    implementation(project(":ai-engine"))
    implementation(project(":llama-cpp"))   // On-device GGUF inference — no Ollama, no server, fully offline
    implementation(project(":smart-memory"))
    implementation(project(":tool-system"))
    implementation(project(":voice-core"))
    implementation(project(":internet"))
    implementation(project(":vision-system"))
    implementation(project(":reliability-system"))

    // ─── Core desugaring (API < 26 back-compat) ──────────────────────────────
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    // ─── Kotlin ─────────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("androidx.multidex:multidex:2.0.1")

    // ─── Jetpack Compose BOM ──────────────────────────────────────────────────
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose core
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.ui:ui-graphics")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Material 3
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Activity + ViewModel Compose integration
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // Hilt + Compose navigation integration
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Compose testing
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    // ─── Security / Biometric ─────────────────────────────────────────────
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.biometric:biometric:1.1.0")

    // ─── AndroidX Core ──────────────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.startup:startup-runtime:1.1.1")

    // ─── Hilt DI ────────────────────────────────────────────────────────
    implementation("com.google.dagger:hilt-android:2.51.1")
    ksp("com.google.dagger:hilt-compiler:2.51.1")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // ─── Media ─────────────────────────────────────────────────────────
    implementation("androidx.media:media:1.7.0")

    // ─── WorkManager ───────────────────────────────────────────────────────
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // ─── Room (local DB) ──────────────────────────────────────────────────────
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ─── DataStore ────────────────────────────────────────────────────────
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ─── CameraX ────────────────────────────────────────────────────────
    val cameraxVersion = "1.4.0"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // ─── ML Kit ─────────────────────────────────────────────────────────
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    implementation("com.google.mlkit:object-detection:17.0.2")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
    implementation("com.google.mlkit:pose-detection-accurate:18.0.0-beta5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // ─── Gemini AI ────────────────────────────────────────────────────────
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")

    // ─── Network ───────────────────────────────────────────────────────
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // ─── Firebase ────────────────────────────────────────────────────────
    implementation(platform("com.google.firebase:firebase-bom:33.6.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-crashlytics-ktx")

    // ─── JavaMail ────────────────────────────────────────────────────────
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")

    // ─── Image loading ──────────────────────────────────────────────────────
    implementation("io.coil-kt:coil-compose:2.7.0")

    // ─── TFLite ─────────────────────────────────────────────────────────
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-api:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-metadata:0.4.4")

    // ─── ONNX Runtime (real wake-word neural model: melspec + embedding + ──
    // ─── our trained "Aladdin" classifier — see wake/WakeWordEngine.kt) ────
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")

    // ─── Testing ────────────────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.51.1")
    kspAndroidTest("com.google.dagger:hilt-compiler:2.51.1")
}
