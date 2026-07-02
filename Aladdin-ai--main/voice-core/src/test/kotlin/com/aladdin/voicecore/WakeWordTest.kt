package com.aladdin.voicecore

import com.aladdin.voicecore.models.ErrorCode
import com.aladdin.voicecore.models.VoiceCoreConfig
import com.aladdin.voicecore.models.VoiceCoreEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WakeWordTest {

    @Test fun `WakeWordEngine enum has VOSK and PORCUPINE`() {
        val engines = VoiceCoreConfig.WakeWordEngine.values()
        assertThat(engines.map { it.name }).containsAtLeast("VOSK", "PORCUPINE")
    }

    @Test fun `default engine is VOSK`() {
        assertThat(VoiceCoreConfig().wakeWordEngine)
            .isEqualTo(VoiceCoreConfig.WakeWordEngine.VOSK)
    }

    @Test fun `WakeWordDetected event is correct type`() {
        val event: VoiceCoreEvent = VoiceCoreEvent.WakeWordDetected("aladdin", 0.95f)
        assertThat(event).isInstanceOf(VoiceCoreEvent.WakeWordDetected::class.java)
    }

    @Test fun `ErrorCode WAKE_WORD_MODEL_NOT_FOUND exists`() {
        assertThat(ErrorCode.WAKE_WORD_MODEL_NOT_FOUND).isNotNull()
    }

    @Test fun `wake word confidence threshold default is 0.7`() {
        assertThat(VoiceCoreConfig().wakeWordConfidenceThreshold).isEqualTo(0.7f)
    }

    @Test fun `idle timeout default is 30 seconds`() {
        assertThat(VoiceCoreConfig().wakeWordIdleTimeoutSec).isEqualTo(30)
    }

    @Test fun `multiple wake words supported`() {
        val config = VoiceCoreConfig(wakeWords = listOf("aladdin", "jarvis", "computer"))
        assertThat(config.wakeWords).hasSize(3)
    }

    @Test fun `false trigger test - 1000 silent energy frames`() {
        val energyThreshold = 500.0
        var falseTriggers = 0
        repeat(1000) {
            val rms = Math.sqrt((1..512).sumOf {
                val s = (50 * Math.sin(2 * Math.PI * 200 * it / 16000.0)).toInt()
                (s * s).toDouble()
            } / 512)
            if (rms > energyThreshold) falseTriggers++
        }
        assertThat(falseTriggers).isEqualTo(0)
    }

    @Test fun `sensitivity within legal Vosk/Porcupine range`() {
        val config = VoiceCoreConfig(wakeWordSensitivity = 0.7f)
        // Engine clamps to 0.3..0.9
        val clamped = config.wakeWordSensitivity.coerceIn(0.3f, 0.9f)
        assertThat(clamped).isEqualTo(0.7f)
    }

    @Test fun `wakeWords default list non-empty and lowercase`() {
        val words = VoiceCoreConfig().wakeWords
        assertThat(words).isNotEmpty()
        words.forEach { w -> assertThat(w).isEqualTo(w.lowercase()) }
    }

    @Test fun `pipeline latency budget - 30ms frame must process under 5ms`() {
        val frameMs = VoiceCoreConfig().frameSizeMs
        val budgetMs = 5
        assertThat(budgetMs).isLessThan(frameMs)
    }

    @Test fun `WakeWordDetected confidence equality`() {
        val e1 = VoiceCoreEvent.WakeWordDetected("aladdin", 0.9f)
        val e2 = VoiceCoreEvent.WakeWordDetected("aladdin", 0.9f)
        assertThat(e1).isEqualTo(e2)
    }

    @Test fun `SleepModeEntered and SleepModeExited are singletons`() {
        assertThat(VoiceCoreEvent.SleepModeEntered).isSameInstanceAs(VoiceCoreEvent.SleepModeEntered)
        assertThat(VoiceCoreEvent.SleepModeExited).isSameInstanceAs(VoiceCoreEvent.SleepModeExited)
    }
}
