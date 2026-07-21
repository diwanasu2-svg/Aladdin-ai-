"""production/apk_optimizer.py — Phase 15, Feature 7: APK Shrinking & Play Store Build.

Generates ProGuard rules, Gradle config for AAB/APK splitting, R8 shrinking,
and automates the Play Store release pipeline.
"""

from __future__ import annotations

import logging
import os
import subprocess
from pathlib import Path
from typing import Dict, List, Optional

log = logging.getLogger(__name__)


PROGUARD_RULES = """\
# ============================================================
# Aladdin AI — ProGuard Rules (Phase 15)
# ============================================================

# Keep AI model interfaces
-keep class com.aladdin.ai.** { *; }
-keep class com.aladdin.models.** { *; }
-keep interface com.aladdin.** { *; }

# Llama.cpp JNI bridge
-keep class com.aladdin.llama.** { *; }
-keepclassmembers class * {
    @com.aladdin.annotation.Keep *;
    native <methods>;
}

# Firebase Crashlytics — keep crash metadata
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Serialization
-keepattributes *Annotation*
-keepattributes Signature
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Kotlin
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# Coroutines
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Gson
-keepattributes Signature
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.** { *; }

# Audio processing
-keep class com.aladdin.audio.** { *; }

# Vision
-keep class com.aladdin.vision.** { *; }

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
"""


GRADLE_RELEASE_CONFIG = """\
// ============================================================
// Aladdin AI — Release Build Config (Phase 15)
// Add this inside android { } in app/build.gradle.kts
// ============================================================

buildTypes {
    release {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
        signingConfig = signingConfigs.getByName("release")

        // Split APKs by ABI for smaller downloads
        splits {
            abi {
                isEnable = true
                reset()
                include("arm64-v8a", "armeabi-v7a", "x86_64")
                isUniversalApk = false
            }
        }
    }
    
    debug {
        applicationIdSuffix = ".debug"
        isDebuggable = true
    }
}

// Android App Bundle (AAB) for Play Store
bundle {
    language {
        enableSplit = true
    }
    density {
        enableSplit = true
    }
    abi {
        enableSplit = true
    }
}

// Signing config (reads from keystore.properties)
signingConfigs {
    create("release") {
        val keystorePropertiesFile = rootProject.file("keystore.properties")
        if (keystorePropertiesFile.exists()) {
            val keystoreProperties = java.util.Properties()
            keystoreProperties.load(java.io.FileInputStream(keystorePropertiesFile))
            keyAlias = keystoreProperties["keyAlias"] as String
            keyPassword = keystoreProperties["keyPassword"] as String
            storeFile = file(keystoreProperties["storeFile"] as String)
            storePassword = keystoreProperties["storePassword"] as String
        }
    }
}
"""


GITHUB_ACTIONS_APK_BUILD = """\
# .github/workflows/release.yml
# Aladdin AI — Release APK / AAB build (Phase 15)

name: Build Release APK & AAB

on:
  push:
    tags:
      - 'v*'
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    
    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3

      - name: Set up NDK
        uses: nttld/setup-ndk@v1
        with:
          ndk-version: r26c

      - name: Cache Gradle
        uses: actions/cache@v3
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: gradle-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}

      - name: Decode keystore
        run: |
          echo "${{ secrets.KEYSTORE_BASE64 }}" | base64 -d > app/release.keystore
          echo "storeFile=release.keystore" > keystore.properties
          echo "storePassword=${{ secrets.KEYSTORE_PASSWORD }}" >> keystore.properties
          echo "keyAlias=${{ secrets.KEY_ALIAS }}" >> keystore.properties
          echo "keyPassword=${{ secrets.KEY_PASSWORD }}" >> keystore.properties

      - name: Build release AAB
        run: ./gradlew bundleRelease --no-daemon

      - name: Build release APK (arm64)
        run: ./gradlew assembleRelease --no-daemon

      - name: Upload AAB artifact
        uses: actions/upload-artifact@v4
        with:
          name: release-aab
          path: app/build/outputs/bundle/release/*.aab

      - name: Upload APK artifact
        uses: actions/upload-artifact@v4
        with:
          name: release-apk
          path: app/build/outputs/apk/release/*.apk

      - name: Create GitHub Release
        uses: ncipollo/release-action@v1
        with:
          artifacts: "app/build/outputs/bundle/release/*.aab,app/build/outputs/apk/release/*.apk"
          token: ${{ secrets.GITHUB_TOKEN }}
"""


class APKOptimizer:
    """Generates all release configuration files and validates build readiness."""

    def __init__(self, project_root: str = ".") -> None:
        self.root = Path(project_root)

    def write_proguard_rules(self, dest: Optional[str] = None) -> Path:
        path = Path(dest) if dest else self.root / "app" / "proguard-rules.pro"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(PROGUARD_RULES)
        log.info("[APKOptimizer] ProGuard rules written: %s", path)
        return path

    def write_gradle_release_config(self, dest: Optional[str] = None) -> Path:
        path = Path(dest) if dest else self.root / "app" / "build_release_config.gradle.kts"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(GRADLE_RELEASE_CONFIG)
        log.info("[APKOptimizer] Gradle release config written: %s", path)
        return path

    def write_github_actions_release(self, dest: Optional[str] = None) -> Path:
        path = Path(dest) if dest else self.root / ".github" / "workflows" / "release.yml"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(GITHUB_ACTIONS_APK_BUILD)
        log.info("[APKOptimizer] GitHub Actions release workflow written: %s", path)
        return path

    def generate_keystore(
        self,
        alias: str = "aladdin",
        dname: str = "CN=Aladdin AI,O=Aladdin,C=US",
        validity: int = 10000,
        dest: Optional[str] = None,
    ) -> Optional[Path]:
        """Generate a development keystore (do NOT use in production without protecting it)."""
        keystore_path = Path(dest) if dest else self.root / "app" / "debug.keystore"
        keystore_path.parent.mkdir(parents=True, exist_ok=True)

        cmd = [
            "keytool", "-genkeypair",
            "-alias", alias,
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", str(validity),
            "-dname", dname,
            "-keystore", str(keystore_path),
            "-storepass", "android",
            "-keypass", "android",
            "-noprompt",
        ]
        try:
            subprocess.run(cmd, check=True, timeout=60)
            log.info("[APKOptimizer] Keystore generated: %s", keystore_path)
            return keystore_path
        except FileNotFoundError:
            log.warning("[APKOptimizer] keytool not found — skipping keystore generation")
        except subprocess.CalledProcessError as exc:
            log.warning("[APKOptimizer] keytool failed: %s", exc)
        return None

    def check_readiness(self) -> Dict[str, bool]:
        """Check that release build prerequisites are in place."""
        checks = {
            "proguard_rules": (self.root / "app" / "proguard-rules.pro").exists(),
            "keystore_properties": (self.root / "keystore.properties").exists(),
            "gradlew_executable": os.access(self.root / "gradlew", os.X_OK),
            "github_ci_workflow": (self.root / ".github" / "workflows" / "release.yml").exists(),
            "gitignore_keystore": self._check_gitignore(),
        }
        for check, result in checks.items():
            status = "✅" if result else "❌"
            log.info("[APKOptimizer] %s %s", status, check)
        return checks

    def _check_gitignore(self) -> bool:
        gi = self.root / ".gitignore"
        if not gi.exists():
            return False
        content = gi.read_text()
        return "keystore.properties" in content

    def setup_all(self) -> None:
        """Write all release configuration files."""
        self.write_proguard_rules()
        self.write_gradle_release_config()
        self.write_github_actions_release()
        log.info("[APKOptimizer] All release files written")
