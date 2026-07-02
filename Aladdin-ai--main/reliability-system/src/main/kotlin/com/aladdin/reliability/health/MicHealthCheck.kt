package com.aladdin.reliability.health

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

class MicHealthCheck(private val context: Context) {

    companion object {
        private const val TAG = "MicHealthCheck"
        private const val SAMPLE_RATE = 16_000
    }

    data class Result(val available: Boolean, val issue: String?)

    fun check(): Result {
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return Result(false, "AudioRecord unsupported on this device")

            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf
            )
            val ok = rec.state == AudioRecord.STATE_INITIALIZED
            rec.release()
            Result(available = ok, issue = if (!ok) "AudioRecord failed to initialise" else null)
        } catch (e: SecurityException) {
            Result(false, "RECORD_AUDIO permission not granted")
        } catch (e: Exception) {
            Log.w(TAG, "Mic check threw: ${e.message}")
            Result(false, "Mic check exception: ${e.message}")
        }
    }
}
