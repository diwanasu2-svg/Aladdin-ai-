# Aladdin `:piper-tts` — On-device offline TTS (male voice)

Runs [Piper](https://github.com/rhasspy/piper) neural text-to-speech
directly on the phone via JNI, using the **male** voice `en_US-ryan-medium`
by default. No network access at synthesis time, no subprocess exec (Piper's
CLI binary approach doesn't work reliably on Android 10+, which blocks
executing arbitrary binaries outside `nativeLibraryDir` — this module links
Piper as a proper `.so` instead).

## Male voice

Default voice: **`en_US-ryan-medium`**. Change it by passing a different
`voice` to `PiperTtsEngine.init(voice = "...")` — any Piper voice from
[rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices) works as
long as its `.onnx` + `.onnx.json` files are present locally.

`voice-core`'s `ModelDownloader` fetches `en_US-ryan-medium.onnx` +
`.onnx.json` once on first app run into `filesDir/models/piper/`; after that
the app never needs the network for TTS again.

## Build

Same pattern as `:llama-cpp` — CMake `FetchContent` pulls a pinned Piper
checkout + links against the onnxruntime Android AAR the first time the
native build runs, then everything is compiled into `libpiper_bridge.so` and
bundled into the APK. Requires the Android NDK + CMake 3.22+.

You'll also need to add the onnxruntime Android AAR dependency so its
prebuilt `libonnxruntime.so` is available to link against:

```kotlin
// piper-tts/build.gradle.kts (dependencies block)
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
```

## Usage

```kotlin
val piper = PiperTtsEngine(context)
lifecycleScope.launch {
    if (piper.init(voice = PiperTtsEngine.DEFAULT_MALE_VOICE)) {
        piper.speak("Hello, I'm Aladdin, running fully offline.")
    }
}
```

## Pipeline position

```
Mic → STT (whisper.cpp) → LlamaCppEngine (local GGUF)
    → PiperTtsEngine (this module, male voice) → Speaker
```

`voice-core`'s `TTSEngine` prefers this JNI path when `libpiper_bridge.so` is
present, falling back to the legacy subprocess / Android TTS paths otherwise.
