package com.aladdin.tools.tools

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.os.Build
import android.util.Log
import androidx.media.session.MediaButtonReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import android.view.KeyEvent

/**
 * Music Control tool — MediaSession + AudioManager.
 *
 * Commands:
 *   play          — start / resume playback
 *   pause         — pause playback
 *   stop          — stop playback
 *   next          — skip to next track
 *   previous      — skip to previous track
 *   volume_up     — raise system volume
 *   volume_down   — lower system volume
 *   volume_set    — set volume to N (0–15)
 *   mute          — mute / unmute
 *   info          — current playback info + volume
 *
 * Params: command, volume (0–15), stream (music|ring|alarm)
 *
 * Note: Controlling other apps' playback requires
 *   MEDIA_CONTENT_CONTROL permission (system-level) or
 *   works via KeyEvent broadcast which is widely supported.
 */
@Singleton
class MusicControlTool @Inject constructor(@ApplicationContext private val context: Context) : BaseTool {

    override val id = "music_control"
    override val name = "Music Control"
    override val description = "Control media playback (play/pause/next/prev) and adjust device volume"

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.Main) {
        val stream = resolveStream(params["stream"])
        when (params["command"] ?: "info") {
            "play"        -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, "▶ Playback started")
            "pause"       -> mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE, "⏸ Playback paused")
            "stop"        -> mediaKey(KeyEvent.KEYCODE_MEDIA_STOP, "⏹ Playback stopped")
            "next"        -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, "⏭ Skipped to next track")
            "previous"    -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "⏮ Went to previous track")
            "play_pause"  -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "⏯ Play/pause toggled")
            "volume_up"   -> adjustVolume(AudioManager.ADJUST_RAISE, stream)
            "volume_down" -> adjustVolume(AudioManager.ADJUST_LOWER, stream)
            "volume_set"  -> setVolume(params["volume"]?.toIntOrNull() ?: 8, stream)
            "mute"        -> toggleMute(stream)
            "volume"      -> volumeInfo(stream)
            else          -> playbackInfo()
        }
    }

    private fun mediaKey(keyCode: Int, message: String): ToolResult {
        return try {
            val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val up   = KeyEvent(KeyEvent.ACTION_UP,   keyCode)
            audioManager.dispatchMediaKeyEvent(down)
            audioManager.dispatchMediaKeyEvent(up)
            Log.d("MusicControlTool", "Media key: $keyCode")
            ToolResult.success(id, "🎵 $message")
        } catch (e: Exception) {
            ToolResult.error(id, "Media key failed: ${e.message}")
        }
    }

    private fun adjustVolume(direction: Int, stream: Int): ToolResult {
        audioManager.adjustStreamVolume(stream, direction, AudioManager.FLAG_SHOW_UI)
        val current = audioManager.getStreamVolume(stream)
        val max = audioManager.getStreamMaxVolume(stream)
        val label = if (direction == AudioManager.ADJUST_RAISE) "🔊 Volume up" else "🔉 Volume down"
        return ToolResult.success(id, "$label: $current/$max")
    }

    private fun setVolume(level: Int, stream: Int): ToolResult {
        val max = audioManager.getStreamMaxVolume(stream)
        val clamped = level.coerceIn(0, max)
        audioManager.setStreamVolume(stream, clamped, AudioManager.FLAG_SHOW_UI)
        return ToolResult.success(id, "🔊 Volume set: $clamped/$max")
    }

    private fun toggleMute(stream: Int): ToolResult {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val isMuted = audioManager.isStreamMute(stream)
            audioManager.adjustStreamVolume(
                stream,
                if (isMuted) AudioManager.ADJUST_UNMUTE else AudioManager.ADJUST_MUTE,
                AudioManager.FLAG_SHOW_UI
            )
            val action = if (isMuted) "🔊 Unmuted" else "🔇 Muted"
            ToolResult.success(id, "$action stream")
        } else {
            audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
            ToolResult.success(id, "🔇 Mute toggled")
        }
    }

    private fun volumeInfo(stream: Int): ToolResult {
        val current = audioManager.getStreamVolume(stream)
        val max = audioManager.getStreamMaxVolume(stream)
        val isMuted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) audioManager.isStreamMute(stream) else false
        val pct = (current * 100 / max.coerceAtLeast(1))
        return ToolResult.success(id, "🔊 Volume: $current/$max ($pct%)${if (isMuted) " [MUTED]" else ""}")
    }

    private fun playbackInfo(): ToolResult {
        val musicVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val musicMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val ringVol  = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        val ringMax  = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
        val mode     = when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_SILENT  -> "Silent 🔕"
            AudioManager.RINGER_MODE_VIBRATE -> "Vibrate 📳"
            else                             -> "Normal 🔔"
        }
        return ToolResult.success(id, buildString {
            appendLine("🎵 Music Control Info:")
            appendLine("  Music volume:  $musicVol/$musicMax")
            appendLine("  Ring volume:   $ringVol/$ringMax")
            appendLine("  Ringer mode:   $mode")
        }.trim())
    }

    private fun resolveStream(name: String?): Int = when (name?.lowercase()) {
        "ring"       -> AudioManager.STREAM_RING
        "alarm"      -> AudioManager.STREAM_ALARM
        "voice_call" -> AudioManager.STREAM_VOICE_CALL
        "notification" -> AudioManager.STREAM_NOTIFICATION
        else         -> AudioManager.STREAM_MUSIC
    }
}
