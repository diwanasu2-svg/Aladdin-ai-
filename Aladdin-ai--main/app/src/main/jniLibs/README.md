# JNI Native Libraries

Place pre-built Piper TTS `.so` files into the ABI subdirectories:

```
jniLibs/
├── arm64-v8a/
│   └── libpiper.so        ← 64-bit ARM (most modern Android devices)
├── armeabi-v7a/
│   └── libpiper.so        ← 32-bit ARM
└── x86_64/
    └── libpiper.so        ← x86_64 (emulators, ChromeOS)
```

## Building libpiper.so

1. Clone the Piper repository: https://github.com/rhasspy/piper
2. Follow the Android NDK cross-compilation guide in `docs/BUILDING.md`
3. Use NDK r26 or later with `minSdkVersion=26`
4. Copy the output `.so` files to the correct ABI subdirectories above

## Fallback Behaviour

If `libpiper.so` is absent (e.g. during development or on unsupported ABIs):
- `PiperJNI.nativeAvailable` returns `false`
- `PiperTTSEngine` automatically falls back to Android `TextToSpeech`
- All safe-wrapper methods (`safeSynthesize`, etc.) are no-ops that return empty results
- No crash or exception is thrown

## ABI filters (build.gradle.kts)

```kotlin
ndk {
    abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64")
}
```
