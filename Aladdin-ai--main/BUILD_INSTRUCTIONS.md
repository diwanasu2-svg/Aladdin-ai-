# Aladdin — Build Instructions

## Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17 (bundled with Android Studio)
- Android SDK 34 (API level 34)
- Min SDK supported: **24** (Android 7.0)

---

## 1. Open in Android Studio

```
File → Open → select the project root folder
```

Wait for Gradle sync to complete.

---

## 2. Configure local.properties

Edit `local.properties` (or create it if missing):

```properties
sdk.dir=/path/to/your/Android/Sdk
GEMINI_API_KEY=your_gemini_api_key_here
```

Get a free Gemini API key at: https://aistudio.google.com/app/apikey

---

## 3. Build a debug APK

### From Android Studio
- **Build → Build Bundle(s)/APK(s) → Build APK(s)**
- Output: `app/build/outputs/apk/debug/Aladdin-debug-v1.0.0.apk`

### From the command line
```bash
./gradlew assembleDebug
```

---

## 4. Build a release APK (signed)

### Set up signing

Copy and fill in `keystore.properties.template` → `keystore.properties`:
```properties
storeFile=../aladdin-release.jks
storePassword=YOUR_PASSWORD
keyAlias=aladdin
keyPassword=YOUR_KEY_PASSWORD
```

Generate a keystore (first time only):
```bash
keytool -genkey -v \
  -keystore aladdin-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias aladdin
```

### Build
```bash
./gradlew assembleRelease
```
Output: `app/build/outputs/apk/release/Aladdin-release-v1.0.0.apk`

---

## 5. Build a release AAB (Play Store)

```bash
./gradlew bundleRelease
```
Output: `app/build/outputs/bundle/release/app-release.aab`

Upload to: https://play.google.com/console

---

## 6. Install on a connected device

```bash
./gradlew installDebug
```

Or via adb:
```bash
adb install -r app/build/outputs/apk/debug/Aladdin-debug-v1.0.0.apk
```

---

## Project Structure

```
app/                          ← Main application module
  src/main/
    AndroidManifest.xml       ← All permissions + component declarations
    kotlin/com/aladdin/app/
      AladdinApp.kt           ← Application class (Hilt + WorkManager init)
      MainActivity.kt         ← Entry point (permissions + service start)
      service/
        AladdinForegroundService.kt   ← PARTIAL_WAKE_LOCK, ongoing notif
        AladdinBackgroundService.kt   ← Network calls, processing
      receiver/
        BootReceiver.kt               ← Auto-start on boot
        NotificationActionReceiver.kt ← Notification button actions
      notification/
        NotificationHelper.kt         ← All notification channels + builders
      permission/
        PermissionManager.kt          ← Runtime permission helpers + rationale
      worker/
        SyncWorker.kt                 ← WorkManager periodic sync
      di/
        AppModule.kt                  ← Hilt dependency graph
    res/
      values/
        strings.xml     ← App strings + channel names
        themes.xml      ← Material 3 theme
        colors.xml      ← Brand colors
      xml/
        backup_rules.xml              ← Cloud backup rules
        data_extraction_rules.xml     ← Android 12+ data extraction

ai-engine/        ← AI model inference
smart-memory/     ← Persistent memory layer
tool-system/      ← Tool/action dispatcher
voice-core/       ← Voice recognition + synthesis
internet/         ← Network layer
vision-system/    ← CameraX, OCR, Gemini Vision, QR, Document Scanner
```

---

## Permissions Reference

| Permission | Purpose | Runtime prompt |
|---|---|---|
| RECORD_AUDIO | Voice commands | Yes |
| CAMERA | Visual analysis | Yes |
| POST_NOTIFICATIONS | Alerts & reminders | Yes (Android 13+) |
| READ_MEDIA_IMAGES | Image processing | Yes (Android 13+) |
| READ_EXTERNAL_STORAGE | File access (Android ≤ 12) | Yes |
| WAKE_LOCK | Keep CPU awake during processing | No |
| FOREGROUND_SERVICE | Run persistent background service | No |
| RECEIVE_BOOT_COMPLETED | Auto-start on reboot | No |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | Prevent Doze from killing service | Via Settings dialog |
| SYSTEM_ALERT_WINDOW | Overlay UI | Via Settings dialog |

---

## Enabling Screen Understanding (Accessibility)

To use the Accessibility-based screen understanding feature:

1. Go to: **Settings → Accessibility → Installed Apps → Aladdin Vision**
2. Enable **Aladdin Vision (Screen Understanding)**
3. Grant the service permission to observe screen content

This is required for `ScreenUnderstandingService` to function.
