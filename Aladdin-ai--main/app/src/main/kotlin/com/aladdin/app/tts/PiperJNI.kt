package com.aladdin.app.tts

import android.util.Log

/**
 * PiperJNI — Special Item 24: JNI wrapper for libpiper.so native Piper TTS.
 *
 * Design:
 *  • Declares all native methods expected by the Piper C++ library
 *  • loadLibrary wrapped in try-catch — graceful fallback when .so is absent
 *  • nativeAvailable flag lets callers decide whether to fall back to Android TTS
 *  • jniLibs directory: app/src/main/jniLibs/{arm64-v8a, armeabi-v7a, x86_64}/libpiper.so
 *
 * To place real libpiper.so:
 *  1. Copy pre-built binaries into app/src/main/jniLibs/<abi>/libpiper.so
 *  2. App gradle already has abiFilters for arm64-v8a, armeabi-v7a, x86_64
 */
object PiperJNI {

    private const val TAG = "PiperJNI"

    /**
     * True when libpiper.so was found and loaded successfully.
     * Always false on Replit / emulator without real .so files.
     */
    @JvmStatic
    val nativeAvailable: Boolean

    init {
        nativeAvailable = try {
            System.loadLibrary("piper")
            Log.i(TAG, "libpiper.so loaded successfully")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "libpiper.so not found — Android TTS fallback will be used. " +
                "Error: ${e.message}")
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "Security error loading libpiper.so: ${e.message}")
            false
        }
    }

    // ─── Native method declarations ───────────────────────────────────────────
    // These are implemented in C++ inside the piper shared library.
    // They are only callable when nativeAvailable == true.

    /**
     * Initialise a Piper TTS context.
     *
     * @param modelPath  Absolute path to the .onnx model file
     * @param configPath Absolute path to the .onnx.json config file
     * @return           Native handle (non-zero on success, 0 on failure)
     */
    @JvmStatic
    external fun init(modelPath: String, configPath: String): Long

    /**
     * Synthesise [text] to a raw PCM WAV file at [outputPath].
     *
     * @param handle     Native context handle from [init]
     * @param text       Text to synthesise
     * @param outputPath Absolute path where the WAV file will be written
     * @return           true on success
     */
    @JvmStatic
    external fun synthesize(handle: Long, text: String, outputPath: String): Boolean

    /**
     * Synthesise [text] to a raw PCM byte array (streaming use-case).
     *
     * @param handle Native context handle from [init]
     * @param text   Text to synthesise
     * @return       PCM byte array (16-bit LE, 22050 Hz, mono), or empty on error
     */
    @JvmStatic
    external fun synthesizeToBytes(handle: Long, text: String): ByteArray

    /**
     * Set synthesis speed (speaking rate).
     *
     * @param handle Native context handle
     * @param speed  Rate multiplier (0.5 = slow, 1.0 = normal, 2.0 = fast)
     */
    @JvmStatic
    external fun setSpeed(handle: Long, speed: Float)

    /**
     * Set synthesis pitch.
     *
     * @param handle Native context handle
     * @param pitch  Pitch multiplier (0.5 = low, 1.0 = normal, 2.0 = high)
     */
    @JvmStatic
    external fun setPitch(handle: Long, pitch: Float)

    /**
     * Release native resources held by [handle].
     * Must be called when the engine is no longer needed to avoid memory leaks.
     */
    @JvmStatic
    external fun free(handle: Long)

    /**
     * Return the Piper library version string, or null if unavailable.
     */
    @JvmStatic
    external fun version(): String?

    // ─── Safe wrappers (no-ops when native unavailable) ───────────────────────

    @JvmStatic
    fun safeInit(modelPath: String, configPath: String): Long {
        if (!nativeAvailable) return 0L
        return try { init(modelPath, configPath) }
        catch (e: UnsatisfiedLinkError) { Log.e(TAG, "safeInit failed: ${e.message}"); 0L }
    }

    @JvmStatic
    fun safeSynthesize(handle: Long, text: String, outputPath: String): Boolean {
        if (!nativeAvailable || handle == 0L) return false
        return try { synthesize(handle, text, outputPath) }
        catch (e: UnsatisfiedLinkError) { Log.e(TAG, "safeSynthesize failed: ${e.message}"); false }
    }

    @JvmStatic
    fun safeSynthesizeToBytes(handle: Long, text: String): ByteArray {
        if (!nativeAvailable || handle == 0L) return ByteArray(0)
        return try { synthesizeToBytes(handle, text) }
        catch (e: UnsatisfiedLinkError) { Log.e(TAG, "safeSynthesizeToBytes failed: ${e.message}"); ByteArray(0) }
    }

    @JvmStatic
    fun safeFree(handle: Long) {
        if (!nativeAvailable || handle == 0L) return
        try { free(handle) }
        catch (e: UnsatisfiedLinkError) { Log.e(TAG, "safeFree failed: ${e.message}") }
    }
}
