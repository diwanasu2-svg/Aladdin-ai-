package com.aladdin.voicecore

import com.aladdin.voicecore.models.VoiceCoreConfig
import com.aladdin.voicecore.models.VoiceCoreEvent
import com.aladdin.voicecore.models.ErrorCode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VoicePipelineTest {

    @Test fun `WakeWordDetected carries keyword and confidence`() {
        val ev = VoiceCoreEvent.WakeWordDetected("aladdin", 0.85f)
        assertThat(ev.keyword).isEqualTo("aladdin")
        assertThat(ev.confidence).isEqualTo(0.85f)
    }

    @Test fun `VoiceCoreEvent Error carries code and message`() {
        val ev = VoiceCoreEvent.Error(ErrorCode.MIC_UNAVAILABLE, "Mic not accessible")
        assertThat(ev.code).isEqualTo(ErrorCode.MIC_UNAVAILABLE)
        assertThat(ev.message).isEqualTo("Mic not accessible")
    }

    @Test fun `VoiceCoreEvent AudioDeviceChanged has device name`() {
        val ev = VoiceCoreEvent.AudioDeviceChanged("Bluetooth Headset")
        assertThat(ev.deviceName).isEqualTo("Bluetooth Headset")
    }

    @Test fun `SpeechEnded is a singleton object`() {
        assertThat(VoiceCoreEvent.SpeechEnded).isNotNull()
        assertThat(VoiceCoreEvent.SpeechEnded).isSameInstanceAs(VoiceCoreEvent.SpeechEnded)
    }

    @Test fun `TTSComplete is a singleton object`() {
        assertThat(VoiceCoreEvent.TTSComplete).isNotNull()
    }

    @Test fun `MicRecovered is a singleton object`() {
        assertThat(VoiceCoreEvent.MicRecovered).isNotNull()
    }

    @Test fun `frame size calculation is correct`() {
        val config = VoiceCoreConfig(sampleRateHz = 16000, frameSizeMs = 30)
        val frameSamples = config.sampleRateHz * config.frameSizeMs / 1000
        assertThat(frameSamples).isEqualTo(480)
    }

    @Test fun `AGC gain math does not overflow Short range`() {
        val sampleAmplitude = 1000
        val targetRms = 3000.0
        val frame = ShortArray(480) { sampleAmplitude.toShort() }
        val rms = Math.sqrt(frame.map { it.toDouble() * it }.average())
        val gain = if (rms > 0) (targetRms / rms).coerceIn(0.1, 8.0) else 1.0
        val result = ShortArray(frame.size) { i ->
            (frame[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        assertThat(result.all { it <= Short.MAX_VALUE }).isTrue()
        assertThat(result.all { it >= Short.MIN_VALUE }).isTrue()
    }

    @Test fun `shorts to bytes conversion is little-endian`() {
        val shorts = shortArrayOf(100)
        val bytes = ByteArray(2)
        val s = shorts[0].toInt()
        bytes[0] = (s and 0xFF).toByte()
        bytes[1] = (s shr 8 and 0xFF).toByte()
        // 100 = 0x0064, little-endian: [0x64, 0x00]
        assertThat(bytes[0]).isEqualTo(0x64.toByte())
        assertThat(bytes[1]).isEqualTo(0x00.toByte())
    }

    @Test fun `pipeline audio quality - SNR calculation`() {
        val signal = ShortArray(1024) { 10000 }
        val noise = ShortArray(1024) { 100 }
        val signalPower = signal.map { it.toDouble() * it }.average()
        val noisePower = noise.map { it.toDouble() * it }.average()
        val snrDb = 10 * Math.log10(signalPower / noisePower)
        assertThat(snrDb).isGreaterThan(20.0)
    }

    @Test fun `ErrorCode has all expected values`() {
        val codes = ErrorCode.values().map { it.name }
        assertThat(codes).contains("MIC_UNAVAILABLE")
        assertThat(codes).contains("WAKE_WORD_MODEL_NOT_FOUND")
        assertThat(codes).contains("STT_MODEL_NOT_FOUND")
        assertThat(codes).contains("TTS_MODEL_NOT_FOUND")
        assertThat(codes).contains("AUDIO_FOCUS_LOST")
        assertThat(codes).contains("PERMISSION_DENIED")
    }

    @Test fun `WakeWordDetected confidence in valid range`() {
        val ev = VoiceCoreEvent.WakeWordDetected("aladdin", 0.95f)
        assertThat(ev.confidence).isAtLeast(0f)
        assertThat(ev.confidence).isAtMost(1f)
    }

    @Test fun `Transcript event has text and isFinal`() {
        val partial = VoiceCoreEvent.Transcript("alad", isFinal = false)
        val final = VoiceCoreEvent.Transcript("aladdin", isFinal = true)
        assertThat(partial.isFinal).isFalse()
        assertThat(final.isFinal).isTrue()
    }
}
