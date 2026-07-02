# Aladdin Voice Core – Android Module

Complete always-on voice pipeline for Android. Kotlin + Coroutines. Service-based architecture.

## Features

| Component | Implementation |
|---|---|
| Wake word | Vosk (grammar-restricted) or Porcupine |
| Keywords | "Aladdin", "Jarvis", "Computer" (configurable) |
| Confidence threshold | 0.7+ (configurable) |
| Wake word sensitivity | 0.3 – 0.9 (runtime-adjustable) |
| Idle timeout | 30s (configurable) |
| STT | Vosk offline + Faster-Whisper HTTP bridge |
| TTS | Piper (streaming, token-by-token) |
| Barge-in | VAD-based, call `stopSpeaking()` |
| Noise suppression | RNNoise (native JNI, software fallback) |
| AEC | WebRTC (hardware via VOICE_COMMUNICATION source) |
| AGC | WebRTC (software implementation included) |
| VAD | WebRTC VAD (native JNI, energy fallback) |
| Audio device | Auto-switch on headset plug/unplug |
| Mic recovery | Auto-retry up to 5× with back-off |
| Sleep mode | After 5 min idle; wake on word or `wakeUp()` |
| Battery saver | Mute pipeline in sleep; PARTIAL_WAKE_LOCK for CPU |
| AudioFocus | TRANSIENT_MAY_DUCK during TTS |
| Architecture | Foreground service + Binder + StateFlow/SharedFlow |

## Setup

### 1. Add the module

In your app's `settings.gradle.kts`:
```kotlin
include(":voice-core")
```

In your app's `build.gradle.kts`:
```kotlin
dependencies {
    implementation(project(":voice-core"))
}
```

### 2. Download models

```bash
chmod +x voice-core/scripts/download_models.sh
./voice-core/scripts/download_models.sh --dest ./models --arch arm64-v8a
adb push models/ /data/data/YOUR.APP.ID/files/models/
```

Or use the in-app downloader:

```kotlin
ModelDownloader(context).downloadAll().collect { progress ->
    when (progress) {
        is DownloadProgress.Downloading -> showProgress(progress.percent)
        is DownloadProgress.Done -> startVoiceCore()
        is DownloadProgress.Error -> showError(progress.message)
        else -> {}
    }
}
```

### 3. Permissions

Add to your app's `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

Request at runtime:
```kotlin
ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
```

### 4. Integrate

```kotlin
// In your Activity/Application
private val voice = VoiceCoreClient(this, VoiceCoreConfig(
    wakeWords = listOf("aladdin", "jarvis", "computer"),
    wakeWordEngine = VoiceCoreConfig.WakeWordEngine.VOSK,
    wakeWordSensitivity = 0.7f,
    wakeWordConfidenceThreshold = 0.7f,
    conversationModeEnabled = false
))

override fun onStart() {
    super.onStart()
    voice.connect()
    voice.onReady = {
        lifecycleScope.launch {
            voice.events?.collect { event ->
                when (event) {
                    is VoiceCoreEvent.WakeWordDetected ->
                        Log.i(TAG, "Wake: ${event.keyword}")
                    is VoiceCoreEvent.Transcript ->
                        if (event.isFinal) handleCommand(event.text)
                    is VoiceCoreEvent.TTSComplete ->
                        Log.i(TAG, "Done speaking")
                    else -> {}
                }
            }
        }
    }
}

override fun onStop() {
    voice.disconnect() // keeps service running in background
    super.onStop()
}

// Speak a response
voice.speak("Hello, how can I help you?")

// Interrupt
voice.stopSpeaking()

// Tune sensitivity
voice.setSensitivity(0.8f)

// Conversation mode (stays listening after TTS)
voice.setConversationMode(true)
```

## Configuration

```kotlin
VoiceCoreConfig(
    // Wake word
    wakeWords = listOf("aladdin", "jarvis", "computer"),
    wakeWordEngine = VoiceCoreConfig.WakeWordEngine.VOSK,  // or PORCUPINE
    wakeWordSensitivity = 0.7f,           // 0.3–0.9
    wakeWordConfidenceThreshold = 0.7f,   // false-trigger gate
    wakeWordIdleTimeoutSec = 30,

    // Porcupine (only needed if wakeWordEngine = PORCUPINE)
    porcupineAccessKey = "YOUR_KEY",

    // Listening
    silenceTimeoutMs = 3_000,
    sleepAfterIdleMs = 300_000,           // 5 minutes
    conversationModeEnabled = false,
    autoResumeAfterTTS = true,

    // Audio
    sampleRateHz = 16_000,
    frameSizeMs = 30,
    enableVAD = true,
    enableNoiseSuppression = true,
    enableAEC = true,
    enableAGC = true,
    vadAggressiveness = 2,               // 0–3

    // STT
    sttModelPath = "models/vosk-model",
    sttTargetLatencyMs = 500,

    // TTS
    ttsModelPath = "models/piper",
    ttsVoice = "en_US-lessac-medium",
    ttsSpeakingRate = 1.0f,
    ttsStreamingEnabled = true,          // token-by-token

    // Battery
    batterySaverEnabled = true
)
```

## Architecture

```
VoiceCoreService (ForegroundService)
├── AudioPipeline          – AudioRecord → VAD → RNNoise → AGC
├── WakeWordEngine         – Vosk/Porcupine wake word detection
├── STTEngine              – Vosk full recognition + Whisper bridge
├── TTSEngine              – Piper streaming TTS → AudioTrack
├── AudioFocusManager      – AudioFocus / speaker management
└── ContinuousListeningManager  – State machine / orchestration
```

## State Machine

```
IDLE → LISTENING_FOR_WAKE_WORD
              │
         wake word detected
              │
              ▼
       CAPTURING_SPEECH ──silence 3s──► PROCESSING_STT
              │ (barge-in)                    │
              ◄──────────────────────────     ▼
                                        SPEAKING_TTS
                                             │
                                    TTS complete
                                             │
                            conversation? ───┤
                            yes: CAPTURING   │
                            no: WAKE_WORD ◄──┘
```

## Porcupine Wake Word Training

1. Go to [Picovoice Console](https://console.picovoice.ai/)
2. Create custom wake words: "Aladdin", "Jarvis", "Computer"
3. Download `*_android.ppn` files
4. Place in `context.filesDir/models/porcupine/`
5. Set `porcupineAccessKey` in config

## Native Libraries

The following JNI libraries are required (included via Gradle dependencies):

| Library | Purpose | Gradle |
|---|---|---|
| `libvosk.so` | Vosk STT/wake word | `com.alphacephei:vosk-android` |
| `libpv_porcupine.so` | Porcupine wake word | `ai.picovoice:porcupine-android` |
| `librnnoise.so` | RNNoise noise suppression | Build from source (see below) |
| `libwebrtc_vad.so` | WebRTC VAD | `io.github.webrtc-sdk:android` |

### Building RNNoise for Android

```bash
git clone https://github.com/xiph/rnnoise
cd rnnoise
# Use Android NDK to build for arm64-v8a
$NDK/build/ndk-build APP_ABI=arm64-v8a
# Copy librnnoise.so to your jniLibs directory
cp libs/arm64-v8a/librnnoise.so app/src/main/jniLibs/arm64-v8a/
```

## Latency Targets

| Operation | Target |
|---|---|
| Wake word detection | < 200ms |
| STT (Vosk, short utterance) | < 500ms |
| TTS first audio chunk | < 300ms |
| End-to-end (wake → response) | < 1000ms |

## License

Apache 2.0
