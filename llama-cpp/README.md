# Aladdin `:llama-cpp` — On-device offline LLM

Runs a local **GGUF** model (default: `gemma-3-1b-it.Q4_K_M.gguf`) directly on
the phone via [llama.cpp](https://github.com/ggml-org/llama.cpp), through a
small JNI bridge. No Ollama server, no cloud API, no internet connection is
required at inference time.

## How it fits the pipeline

```
Mic → STT (whisper.cpp, :voice-core) → LlamaCppEngine (this module)
    → Piper TTS male voice (:piper-tts) → Speaker
```

`ai-engine`'s `LLMClient` gets a new `LLMProvider.LLAMACPP` option that
delegates to `LlamaCppEngine` instead of calling Gemini or an Ollama HTTP
server — see `ai-engine/src/main/kotlin/com/aladdin/engine/llm/LLMClient.kt`.

## Build

The native target is wired through CMake `FetchContent`, so the first
Gradle build of this module will:

1. Shallow-clone a **pinned** llama.cpp tag (`b4600` — bump it in
   `src/main/cpp/CMakeLists.txt` when you want a newer llama.cpp).
2. Compile `libllama`, `libcommon`, `libggml` and this module's
   `llama_bridge.cpp` into `libllama_bridge.so` for `arm64-v8a`,
   `armeabi-v7a`, and `x86_64`.
3. Bundle those `.so` files straight into the APK — from then on, running
   the app needs **zero network access**.

This requires the Android NDK (r26+) and CMake 3.22+, both installable from
Android Studio → SDK Manager → SDK Tools. Building the native target does
need internet the *first* time (to fetch llama.cpp source); the resulting
APK does not.

## Getting the model onto the device

`gemma-3-1b-it.Q4_K_M.gguf` (~700 MB–1 GB depending on quantization) is far
too large to commit into this git repository. Instead:

- `voice-core`'s `ModelDownloader` has a `ModelSpec` for it and will fetch it
  once, on first app run, into `filesDir/models/llama/`. After that first
  download the app is 100% offline.
- Or push it manually for development:
  ```bash
  adb push gemma-3-1b-it.Q4_K_M.gguf /sdcard/Android/data/com.aladdin.app/files/models/llama/
  # then have the app copy/move it into context.filesDir/models/llama/
  ```

## Usage

```kotlin
val llama = LlamaCppEngine(context)
lifecycleScope.launch {
    if (llama.init()) {
        llama.completeStreaming("What's the capital of France?")
            .collect { token -> ttsEngine.enqueueSentence(token) }
    }
}
```
