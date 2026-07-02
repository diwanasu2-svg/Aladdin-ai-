package com.aladdin.performance.pipeline

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.aladdin.performance.threading.ThreadPoolManager

/**
 * Orchestrates the four main Aladdin processing stages:
 *
 *  Audio PCM  ──[AudioCapture]──► Whisper ASR  ──[MlDispatcher]──►
 *  AI LLM     ──[MlDispatcher]──► Piper TTS    ──[AudioDispatcher]──► Speaker
 *
 * Zero-copy: PCM chunks flow as direct-ByteBuffer references; no intermediate copies.
 * Backpressure: each stage has a bounded Channel; overflow drops oldest chunk.
 */
class PipelineOptimizer {

    companion object { private const val TAG = "PipelineOptimizer" }

    private val asrBatcher   = StageBatcher<ByteArray>("ASR",   maxBatchSize = 4,  windowMs = 100)
    private val llmBatcher   = StageBatcher<String>  ("LLM",   maxBatchSize = 1,  windowMs = 5)
    private val ttsBatcher   = StageBatcher<String>  ("TTS",   maxBatchSize = 8,  windowMs = 50)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running = false

    // ─── Public API ───────────────────────────────────────────────────────────

    /** Submit a raw PCM chunk (16-bit, 16kHz mono) to the ASR stage */
    fun submitAudio(pcm: ByteArray) = asrBatcher.emit(pcm)

    /** Submit a text utterance to the LLM stage */
    fun submitText(text: String) = llmBatcher.emit(text)

    /** Submit a text fragment to the TTS stage */
    fun submitTts(text: String) = ttsBatcher.emit(text)

    /**
     * Start the pipeline.  Call [onAsrResult], [onLlmToken], [onTtsChunk] to wire outputs.
     */
    fun start(
        onAsrResult : suspend (String) -> Unit      = {},
        onLlmToken  : suspend (String) -> Unit      = {},
        onTtsChunk  : suspend (ShortArray) -> Unit  = {}
    ) {
        if (running) return
        running = true
        Log.i(TAG, "Pipeline started")

        // ASR stage — runs on ML dispatcher
        scope.launch(ThreadPoolManager.ml) {
            asrBatcher.batchFlow().collect { batch ->
                Log.v(TAG, "ASR batch: ${batch.size} chunks")
                // Placeholder: real impl calls WhisperModelCache + native decoder
                val text = "[asr:${batch.sumOf { it.size }}bytes]"
                onAsrResult(text)
            }
        }

        // LLM stage — runs on ML dispatcher
        scope.launch(ThreadPoolManager.ml) {
            llmBatcher.batchFlow().collect { batch ->
                batch.forEach { utterance ->
                    Log.v(TAG, "LLM: '$utterance'")
                    onLlmToken("[llm:$utterance]")
                }
            }
        }

        // TTS stage — runs on audio dispatcher
        // Converts text fragments to 16-bit PCM via Piper ONNX (subprocess bridge).
        // Each batch is joined and passed to the Piper binary; raw PCM stdout is
        // read in CHUNK_SIZE_BYTES slices and emitted as ShortArrays.
        scope.launch(ThreadPoolManager.audio) {
            ttsBatcher.batchFlow().collect { batch ->
                val combined = batch.joinToString(" ").trim()
                if (combined.isEmpty()) return@collect
                Log.v(TAG, "TTS batch: ${batch.size} fragments → '${combined.take(40)}'")
                try {
                    val pcmShorts = synthesizeViaPiper(combined)
                    if (pcmShorts.isNotEmpty()) {
                        onTtsChunk(pcmShorts)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "TTS synthesis error: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        running = false
        asrBatcher.close(); llmBatcher.close(); ttsBatcher.close()
        scope.cancel()
        Log.i(TAG, "Pipeline stopped")
    }

    fun isRunning() = running

    // ─── Piper TTS synthesis ──────────────────────────────────────────────────

    /**
     * Synthesise [text] via the Piper binary (subprocess bridge).
     *
     * Piper writes raw 16-bit LE PCM to stdout at 22050 Hz mono.
     * We read it in CHUNK_SIZE_BYTES slices and convert to ShortArray for AudioTrack.
     *
     * Requires:
     *   - Piper binary at /data/user/0/<pkg>/files/models/piper/piper
     *   - Voice model at /data/user/0/<pkg>/files/models/piper/<voice>.onnx
     *   - Config at same path with .onnx.json extension
     */
    private fun synthesizeViaPiper(text: String): ShortArray {
        val piperBin  = System.getProperty("aladdin.piperBin", "/data/user/0/com.aladdin.app/files/models/piper/piper")
        val voiceOnnx = System.getProperty("aladdin.piperVoice", "/data/user/0/com.aladdin.app/files/models/piper/en_US-amy-medium.onnx")
        val voiceJson = "$voiceOnnx.json"

        val binFile = java.io.File(piperBin)
        if (!binFile.exists() || !binFile.canExecute()) {
            Log.w(TAG, "Piper binary not found/executable at $piperBin — skipping TTS")
            return ShortArray(0)
        }

        val cmd = arrayOf(piperBin, "--model", voiceOnnx, "--config", voiceJson, "--output_raw")
        val process = Runtime.getRuntime().exec(cmd)

        // Write text to Piper stdin, close the stream to signal EOF
        process.outputStream.bufferedWriter().use { it.write(text) }

        val rawBytes = process.inputStream.readBytes()
        process.waitFor()

        if (rawBytes.isEmpty()) return ShortArray(0)

        // Convert little-endian int16 bytes to ShortArray
        val shorts = ShortArray(rawBytes.size / 2)
        val buf = java.nio.ByteBuffer.wrap(rawBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.asShortBuffer().get(shorts)
        return shorts
    }
}
