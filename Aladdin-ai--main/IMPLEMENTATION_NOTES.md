# Aladdin AI — Phase 1 Implementation Notes

## Changes Made

### 1. WeatherTool Bug Fix ✅
**File:** `tool-system/.../manager/ToolManager.kt`
**Bug:** Route used `"weather"` but `WeatherTool.id = "weather.fetch"`.  
`execute("weather", params)` returned `"Unknown tool: weather"`.  
**Fix:** Route key changed to `"weather.fetch"` to match the tool's ID exactly.  
Also added `"weather.fetch"` case in `autoExtractParams`.

---

### 2. AIEngine → ForegroundService Wiring ✅
**File:** `app/.../service/AladdinForegroundService.kt`
- Injects `JarvisOrchestrator` and `WakeWordDetector` via Hilt
- Holds `PARTIAL_WAKE_LOCK` so CPU stays awake in background
- Calls `orchestrator.start()` on service start
- Routes `wakeWordDetector.wakeEvents` → `orchestrator.onWakeWordDetected()`
- Updates foreground notification from `orchestrator.statusFlow`

---

### 3. JarvisOrchestrator — Central Coordinator ✅
**File:** `app/.../orchestrator/JarvisOrchestrator.kt`
- Connects `AIEngine`, `ConversationManager`, `WakeWordDetector`
- Exposes `statusFlow`, `conversationFlow`, `isListening`, `isThinking`
- Routes: wake word → speak "Yes how can I help?"
- Routes: text/voice input → `AIEngine.process()` → TTS event
- Background maintenance loop every 5 min (flushContext + runBackgroundMaintenance)
- `ConversationEvent` sealed class for UI/service communication

---

### 4. Jetpack Compose UI ✅
**Files created:**
- `app/.../ui/AladdinApp.kt` — Bottom nav scaffold (Chat / Memory / Tools / Settings)
- `app/.../ui/screens/ChatScreen.kt` — Full conversation UI with:
  - Animated chat bubbles (user/assistant/error)
  - Animated typing indicator (3-dot bounce)
  - Voice button with pulse animation when listening
  - Download progress banner
  - Empty state illustration
- `app/.../ui/screens/MemoryScreen.kt` — Searchable memory list with type icons
- `app/.../ui/screens/ToolsScreen.kt` — Expandable tool cards with examples
- `app/.../ui/screens/SettingsScreen.kt` — Toggle switches for all features
- `app/.../ui/theme/Theme.kt` — Material 3 dark/light theme with dynamic color

**build.gradle.kts** updated:
- Compose BOM `2024.12.01`
- `androidx.compose.material3:material3`
- `androidx.compose.material:material-icons-extended`
- `androidx.activity:activity-compose`
- `androidx.lifecycle:lifecycle-viewmodel-compose`
- `androidx.navigation:navigation-compose`
- Compose plugin: `org.jetbrains.kotlin.plugin.compose` v2.0.21

---

### 5. MainViewModel — Full State Management ✅
**File:** `app/.../MainViewModel.kt`
- `AladdinUiState` data class with messages, isListening, isThinking, download progress
- Observes all `JarvisOrchestrator` flows
- `sendMessage()`, `startListening()`, `stopListening()`, `clearConversation()`
- `setDownloadProgress()` / `clearDownloadProgress()` for model download UI

---

### 6. WakeWordDetector — TFLite Support ✅
**File:** `app/.../wakeword/WakeWordDetector.kt`
- Loads TFLite model from `{filesDir}/models/wakeword/aladdin_wakeword.tflite`
- Uses reflection to avoid compile-time TFLite dep (graceful fallback)
- Falls back to energy-threshold heuristic if model file not present
- Energy gate → TFLite inference → sliding-window vote → WakeEvent

---

### 7. WebRTCVAD — Voice Activity Detection ✅
**File:** `app/.../vad/WebRTCVAD.kt`
- JNI wrapper around `libwebrtc_vad.so`
- Place `.so` in `app/src/main/jniLibs/<abi>/`
- Falls back to RMS energy threshold when native lib missing
- Modes 0–3 (quality → very aggressive)
- `isSpeech(ShortArray)`, `isSpeechFromBytes(ByteArray)` APIs

---

### 8. RNNoise — Noise Suppression ✅
**File:** `app/.../noise/RNNoise.kt`
- JNI wrapper around `librnnoise.so`
- Place `.so` in `app/src/main/jniLibs/<abi>/`
- Falls back to passthrough when native lib missing
- `process(FloatArray, FloatArray): Float` — returns voice activity probability
- `processBuffer(ShortArray)` — batch-processes longer audio buffers

---

### 9. EmbeddingModel — TFLite MiniLM ✅
**File:** `app/.../embedding/EmbeddingModel.kt`
- Loads `{filesDir}/models/minilm/minilm-l6-v2.tflite` via reflection
- 384-dimensional normalized embeddings
- Falls back to deterministic bag-of-words when model missing
- `cosineSimilarity(a, b)` and `findMostSimilar(query, candidates)` helpers

---

### 10. ModelDownloaderHelper — Auto-download Pipeline ✅
**File:** `app/.../download/ModelDownloaderHelper.kt`
- Wraps `voice-core/ModelDownloader` with extra model specs:
  - MiniLM TFLite from HuggingFace (22MB)
  - Wake word TFLite model (3MB)
- Plus existing: Vosk STT (40MB), Piper binary (30MB), Piper voice (63MB)
- Idempotent: skips already-downloaded models
- Progress callbacks fed into `MainViewModel.setDownloadProgress()`

---

## Build Setup

Add to `local.properties`:
```
GEMINI_API_KEY=your_gemini_api_key_here
```

## Native Libraries (Optional — app works without them)

- **WebRTC VAD:** Place `libwebrtc_vad.so` in `app/src/main/jniLibs/<abi>/`
- **RNNoise:** Place `librnnoise.so` in `app/src/main/jniLibs/<abi>/`
- Build from source: https://github.com/xiph/rnnoise

## First Run Behaviour

1. App requests microphone + notification permissions
2. `ModelDownloaderHelper.downloadModels()` downloads missing models (~136MB total)
3. `AladdinForegroundService` starts, acquires wake lock
4. Wake word detector listens for "Aladdin"
5. On detection → STT → AIEngine → TTS response

## Firebase / FCM

Replace `app/google-services.json` with your own Firebase project config.
See: https://firebase.google.com/docs/android/setup
