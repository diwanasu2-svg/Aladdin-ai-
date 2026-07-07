# Aladdin — Fully Offline On-Device Voice AI Pipeline

This document describes the offline pipeline added to the app:

```
🎤 Mic
  ↓
Speech-to-Text        — whisper.cpp JNI (voice-core/speech/STTEngine.kt), already offline
  ↓
llama.cpp             — new :llama-cpp module (JNI bridge to llama.cpp)
  ↓
GGUF Model            — gemma-3-1b-it.Q4_K_M.gguf (downloaded once, then local)
  ↓
Piper TTS (male)      — new :piper-tts module (JNI bridge, voice: en_US-ryan-medium)
  ↓
🔊 Speaker
```

## What changed

| Area | Change |
|---|---|
| `:llama-cpp` (new module) | JNI bridge to llama.cpp; loads a local `.gguf` file and runs completions fully on-device. |
| `:piper-tts` (new module) | JNI bridge to Piper; synthesizes speech from local ONNX voice files, default **male** voice `en_US-ryan-medium`, plays via `AudioTrack`. |
| `ai-engine/models/Models.kt` | Added `LLMProvider.LLAMACPP` (now the **default** provider) + `llamaCppModelPath/ContextSize/Threads/MaxTokens` config fields. |
| `ai-engine/llm/LLMClient.kt` | Added `completeLlamaCpp` / `chatLlamaCpp` / streaming branch that delegate to `LlamaCppEngine`. Gemini/Ollama remain as optional network providers. |
| `ai-engine/di/AIEngineModule.kt` | Default config now uses `LLMProvider.LLAMACPP`; `LLMClient` now takes an `@ApplicationContext Context`. |
| `voice-core/speech/TTSEngine.kt` | Prefers the new JNI `PiperTtsEngine` (male voice) over the old subprocess `piper` binary path, which doesn't reliably work on Android 10+. |
| `voice-core/models/VoiceCoreConfig.kt` | Default `ttsVoice` changed to `"en_US-ryan-medium"` (male). |
| `voice-core/models/ModelDownloader.kt` | Added `gemma3-1b-q4` GGUF spec and `en_US-ryan-medium` (male) Piper voice spec; new `OFFLINE_PIPELINE_MODELS` list is what gets downloaded by default. |
| `settings.gradle.kts` | Registers the two new modules. |

## End result

- ✅ The whole pipeline (STT → LLM → TTS) runs **inside the APK**, on-device.
- ✅ **No Ollama** server needed — llama.cpp runs the GGUF model directly in-process.
- ✅ **No internet** needed at runtime — only the very first app launch needs
  network access, to download the GGUF model + voice files once via
  `ModelDownloader`. After that, airplane mode works fine.
- ✅ **Male voice** — Piper's `en_US-ryan-medium` voice, wired as the default.

## What you still need to do locally to produce a working APK

This repo intentionally does **not** vendor multi-hundred-MB binaries
(llama.cpp/Piper source, the GGUF model, the voice `.onnx` file) — that's
not practical to commit to git or push through an API. Instead:

1. **Open the project in Android Studio** with a recent NDK (r26+) and
   CMake 3.22+ installed (SDK Manager → SDK Tools).
2. **Build once** — the first Gradle sync/build will use CMake
   `FetchContent` to pull pinned llama.cpp / Piper source and compile
   `libllama_bridge.so` / `libpiper_bridge.so` for `arm64-v8a`,
   `armeabi-v7a`, `x86_64`. This step needs internet; the resulting APK
   does not.
3. **First app run** — the app calls `ModelDownloader(context).downloadAll()`
   (already wired up in `voice-core`) to fetch:
   - `gemma-3-1b-it.Q4_K_M.gguf` (~800 MB)
   - `en_US-ryan-medium.onnx` + `.onnx.json` (~63 MB, male voice)
   - the existing Whisper STT model, wake-word model, etc.
   Once done, the app is fully offline from then on.
4. Optionally bump the pinned llama.cpp / Piper `GIT_TAG` in each module's
   `src/main/cpp/CMakeLists.txt` to track newer upstream releases.

See `llama-cpp/README.md` and `piper-tts/README.md` for module-level detail.
