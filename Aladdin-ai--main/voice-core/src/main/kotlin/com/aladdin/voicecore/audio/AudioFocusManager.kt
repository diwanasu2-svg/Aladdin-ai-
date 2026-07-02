package com.aladdin.voicecore.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.aladdin.voicecore.models.ErrorCode
import com.aladdin.voicecore.models.VoiceCoreEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Manages Android AudioFocus for the Voice Core.
 *
 * Strategy:
 *  - During TTS playback: request AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
 *    so media apps lower their volume while Aladdin speaks.
 *  - During mic capture: no focus request is needed (capture is independent).
 *  - On focus loss: pause TTS, resume listening.
 *  - On ducking: reduce TTS volume rather than stopping entirely.
 */
class AudioFocusManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioFocusManager"
        private const val TTS_VOLUME_DUCK = 0.3f
        private const val TTS_VOLUME_NORMAL = 1.0f
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _events = MutableSharedFlow<VoiceCoreEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<VoiceCoreEvent> = _events

    @Volatile var isFocusHeld = false
        private set

    var onFocusLost: (() -> Unit)? = null
    var onFocusGained: (() -> Unit)? = null
    var onDuck: ((volume: Float) -> Unit)? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "AudioFocus gained")
                isFocusHeld = true
                onDuck?.invoke(TTS_VOLUME_NORMAL)
                onFocusGained?.invoke()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.i(TAG, "AudioFocus lost permanently")
                isFocusHeld = false
                onFocusLost?.invoke()
                _events.tryEmit(VoiceCoreEvent.Error(ErrorCode.AUDIO_FOCUS_LOST, "Audio focus lost permanently"))
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.i(TAG, "AudioFocus lost transiently")
                isFocusHeld = false
                onFocusLost?.invoke()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.i(TAG, "AudioFocus – ducking")
                onDuck?.invoke(TTS_VOLUME_DUCK)
            }
        }
    }

    private var focusRequest: AudioFocusRequest? = null

    /**
     * Request transient audio focus for TTS playback.
     * @return true if focus was granted immediately
     */
    fun requestFocusForTTS(): Boolean {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .setWillPauseWhenDucked(false)
                .setAcceptsDelayedFocusGain(false)
                .build()
            audioManager.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }

        isFocusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.i(TAG, "AudioFocus request for TTS: ${if (isFocusHeld) "GRANTED" else "DENIED"}")
        return isFocusHeld
    }

    /** Release audio focus when TTS finishes. */
    fun releaseFocus() {
        if (!isFocusHeld) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
        isFocusHeld = false
        focusRequest = null
        Log.i(TAG, "AudioFocus released")
    }

    /** Mutes the stream while Aladdin's TTS is playing to avoid feedback. */
    fun setSpeakerVolume(volume: Float) {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val scaled = (volume * maxVolume).toInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, scaled, 0)
    }
}
