package com.aladdin.voicecore

import com.aladdin.voicecore.audio.VADProcessor
import com.aladdin.voicecore.models.VoiceCoreConfig
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VADProcessorTest {

    private lateinit var vad: VADProcessor
    private val config = VoiceCoreConfig()

    @Before
    fun setUp() {
        vad = VADProcessor(config)
        // init() calls loadLibrary internally; it falls back to energy-based VAD
        vad.init()
    }

    private fun silentBuffer(size: Int = 512): ShortArray = ShortArray(size) { 0 }

    private fun loudBuffer(amplitude: Int = 15000, size: Int = 512): ShortArray =
        ShortArray(size) { i ->
            (amplitude * sin(2 * PI * 440 * i / 16000.0)).toInt().toShort()
        }

    private fun quietBuffer(amplitude: Int = 50, size: Int = 512): ShortArray =
        ShortArray(size) { i ->
            (amplitude * sin(2 * PI * 440 * i / 16000.0)).toInt().toShort()
        }

    @Test fun `silent buffer returns false for energy VAD`() {
        // Energy below ENERGY_THRESHOLD (500) — should return false
        val result = vad.isSpeech(silentBuffer())
        assertThat(result).isFalse()
    }

    @Test fun `loud buffer eventually triggers VAD`() {
        // Needs speechRunRequired (2) consecutive true frames
        vad.isSpeech(loudBuffer())
        val result = vad.isSpeech(loudBuffer())
        assertThat(result).isTrue()
    }

    @Test fun `reset clears speech run counter`() {
        vad.isSpeech(loudBuffer())
        vad.isSpeech(loudBuffer())  // now detected
        vad.reset()
        // After reset, single loud frame won't detect yet (needs 2)
        val result = vad.isSpeech(loudBuffer())
        assertThat(result).isFalse()
    }

    @Test fun `release does not throw`() {
        vad.release()  // should not throw even if native handle is 0
    }

    @Test fun `silent frames do not accumulate speech run`() {
        repeat(20) { vad.isSpeech(silentBuffer()) }
        val result = vad.isSpeech(silentBuffer())
        assertThat(result).isFalse()
    }

    @Test fun `quiet audio below threshold not speech`() {
        val result = vad.isSpeech(quietBuffer(amplitude = 100))
        assertThat(result).isFalse()
    }

    @Test fun `empty buffer does not crash`() {
        vad.isSpeech(ShortArray(0))  // should not throw
    }

    @Test fun `speech run resets after silence`() {
        vad.isSpeech(loudBuffer())
        vad.isSpeech(silentBuffer())  // breaks the run
        vad.isSpeech(loudBuffer())    // run restarted
        val result = vad.isSpeech(loudBuffer())
        assertThat(result).isTrue()
    }

    @Test fun `performance - 1000 frames processed under 2 seconds`() {
        val buffer = ShortArray(512)
        val start = System.currentTimeMillis()
        repeat(1000) { vad.isSpeech(buffer) }
        assertThat(System.currentTimeMillis() - start).isLessThan(2000L)
    }

    @Test fun `VoiceCoreConfig default values are sane`() {
        val cfg = VoiceCoreConfig()
        assertThat(cfg.sampleRateHz).isEqualTo(16000)
        assertThat(cfg.vadAggressiveness).isAtLeast(0)
        assertThat(cfg.vadAggressiveness).isAtMost(3)
        assertThat(cfg.frameSizeMs).isEqualTo(30)
        assertThat(cfg.enableVAD).isTrue()
    }

    @Test fun `wakeWordSensitivity in valid range`() {
        val cfg = VoiceCoreConfig()
        assertThat(cfg.wakeWordSensitivity).isAtLeast(0.3f)
        assertThat(cfg.wakeWordSensitivity).isAtMost(0.9f)
    }

    @Test fun `default wake words list is non-empty`() {
        val cfg = VoiceCoreConfig()
        assertThat(cfg.wakeWords).isNotEmpty()
        assertThat(cfg.wakeWords).contains("aladdin")
    }

    @Test fun `battery saver preset exists`() {
        val cfg = VoiceCoreConfig.batterySaver()
        assertThat(cfg).isNotNull()
        assertThat(cfg.vadAggressiveness).isEqualTo(3)
    }
}
