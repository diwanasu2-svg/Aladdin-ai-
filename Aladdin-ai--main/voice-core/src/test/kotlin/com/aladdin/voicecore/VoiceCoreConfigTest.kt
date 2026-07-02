package com.aladdin.voicecore

import com.aladdin.voicecore.models.VoiceCoreConfig
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VoiceCoreConfigTest {

    // ── Default configuration ──────────────────────────────────────────────────

    @Test fun `default wake words contains aladdin`() {
        assertThat(VoiceCoreConfig().wakeWords).contains("aladdin")
    }

    @Test fun `default sample rate is 16kHz`() {
        assertThat(VoiceCoreConfig().sampleRateHz).isEqualTo(16000)
    }

    @Test fun `default frame size is 30ms`() {
        assertThat(VoiceCoreConfig().frameSizeMs).isEqualTo(30)
    }

    @Test fun `default VAD is enabled`() {
        assertThat(VoiceCoreConfig().enableVAD).isTrue()
    }

    @Test fun `default noise suppression is enabled`() {
        assertThat(VoiceCoreConfig().enableNoiseSuppression).isTrue()
    }

    @Test fun `default AEC is enabled`() {
        assertThat(VoiceCoreConfig().enableAEC).isTrue()
    }

    @Test fun `default AGC is enabled`() {
        assertThat(VoiceCoreConfig().enableAGC).isTrue()
    }

    @Test fun `default VAD aggressiveness is 2`() {
        assertThat(VoiceCoreConfig().vadAggressiveness).isEqualTo(2)
    }

    @Test fun `default silence timeout is 3 seconds`() {
        assertThat(VoiceCoreConfig().silenceTimeoutMs).isEqualTo(3_000L)
    }

    @Test fun `default TTS speaking rate is 1x`() {
        assertThat(VoiceCoreConfig().ttsSpeakingRate).isEqualTo(1.0f)
    }

    @Test fun `default STT language is English`() {
        assertThat(VoiceCoreConfig().sttLanguage).isEqualTo("en")
    }

    @Test fun `default wake word engine is VOSK`() {
        assertThat(VoiceCoreConfig().wakeWordEngine)
            .isEqualTo(VoiceCoreConfig.WakeWordEngine.VOSK)
    }

    // ── Battery saver preset ──────────────────────────────────────────────────

    @Test fun `batterySaver preset has max VAD aggressiveness`() {
        assertThat(VoiceCoreConfig.batterySaver().vadAggressiveness).isEqualTo(3)
    }

    @Test fun `batterySaver preset enables battery saver`() {
        assertThat(VoiceCoreConfig.batterySaver().batterySaverEnabled).isTrue()
    }

    // ── Config customization ──────────────────────────────────────────────────

    @Test fun `copy config with custom wake words`() {
        val config = VoiceCoreConfig(wakeWords = listOf("jarvis", "hey jarvis"))
        assertThat(config.wakeWords).containsExactly("jarvis", "hey jarvis")
        assertThat(config.wakeWords).doesNotContain("aladdin")
    }

    @Test fun `config with Porcupine engine`() {
        val config = VoiceCoreConfig(
            wakeWordEngine = VoiceCoreConfig.WakeWordEngine.PORCUPINE,
            porcupineAccessKey = "test-key"
        )
        assertThat(config.wakeWordEngine).isEqualTo(VoiceCoreConfig.WakeWordEngine.PORCUPINE)
        assertThat(config.porcupineAccessKey).isEqualTo("test-key")
    }

    @Test fun `config equality`() {
        val c1 = VoiceCoreConfig()
        val c2 = VoiceCoreConfig()
        assertThat(c1).isEqualTo(c2)
    }

    @Test fun `config inequality`() {
        val c1 = VoiceCoreConfig(sampleRateHz = 16000)
        val c2 = VoiceCoreConfig(sampleRateHz = 44100)
        assertThat(c1).isNotEqualTo(c2)
    }

    @Test fun `frame size in samples is correct`() {
        val config = VoiceCoreConfig()
        val frameSizeSamples = config.sampleRateHz * config.frameSizeMs / 1000
        assertThat(frameSizeSamples).isEqualTo(480)  // 16000 * 30 / 1000
    }

    @Test fun `wake word confidence threshold is in range`() {
        val config = VoiceCoreConfig()
        assertThat(config.wakeWordConfidenceThreshold).isAtLeast(0f)
        assertThat(config.wakeWordConfidenceThreshold).isAtMost(1f)
    }

    @Test fun `max CPU budget is positive`() {
        assertThat(VoiceCoreConfig().maxCpuPercent).isGreaterThan(0)
    }
}
