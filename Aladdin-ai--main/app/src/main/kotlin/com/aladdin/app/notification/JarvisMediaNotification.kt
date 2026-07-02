package com.aladdin.app.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import com.aladdin.app.MainActivity
import com.aladdin.app.receiver.JarvisActionReceiver
import javax.inject.Inject
import javax.inject.Singleton

private const val NOTIFICATION_ID = 2001

/**
 * JarvisMediaNotification — builds a MediaStyle ongoing notification with:
 *  - 🎤 Voice Input button  → starts listening
 *  - ⏸ Pause / ▶ Resume    → pause/resume current TTS speech
 *  - ⏹ Stop                 → stop Jarvis service entirely
 *
 * MediaSession is used so Android media controls (lock screen, BT headset buttons)
 * also route to Aladdin.
 */
@Singleton
class JarvisMediaNotification @Inject constructor(
    private val context: Context
) {
    private var mediaSession: MediaSessionCompat? = null

    fun initMediaSession(): MediaSessionCompat {
        return MediaSessionCompat(context, "AladdinMediaSession").also { session ->
            session.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE
                    )
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1f)
                    .build()
            )
            session.isActive = true
            mediaSession = session
        }
    }

    fun build(
        session: MediaSessionCompat,
        statusText: String = "Listening…",
        isSpeaking: Boolean = false
    ): Notification {

        // ─── PendingIntents for each action ───────────────────────────────────

        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val micIntent = pendingBroadcast(JarvisActionReceiver.ACTION_MIC, 10)
        val pauseResumeIntent = if (isSpeaking)
            pendingBroadcast(JarvisActionReceiver.ACTION_PAUSE_TTS, 11)
        else
            pendingBroadcast(JarvisActionReceiver.ACTION_RESUME_TTS, 12)
        val stopIntent = pendingBroadcast(JarvisActionReceiver.ACTION_STOP_JARVIS, 13)

        // ─── Actions ──────────────────────────────────────────────────────────

        val micAction = NotificationCompat.Action(
            android.R.drawable.ic_btn_speak_now, "Speak", micIntent
        )
        val pauseAction = NotificationCompat.Action(
            if (isSpeaking) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (isSpeaking) "Pause" else "Resume",
            pauseResumeIntent
        )
        val stopAction = NotificationCompat.Action(
            android.R.drawable.ic_media_previous, "Stop", stopIntent
        )

        // ─── MediaStyle notification ──────────────────────────────────────────

        return NotificationCompat.Builder(context, NotificationHelper.CHANNEL_FOREGROUND)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Aladdin")
            .setContentText(statusText)
            .setSubText(if (isSpeaking) "Speaking" else "Listening")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)   // shows on lock screen
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(micAction)          // index 0
            .addAction(pauseAction)        // index 1
            .addAction(stopAction)         // index 2
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)   // show all 3 in compact view
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(stopIntent)
            )
            .build()
    }

    fun getNotificationId() = NOTIFICATION_ID

    fun releaseSession() {
        mediaSession?.release()
        mediaSession = null
    }

    private fun pendingBroadcast(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, JarvisActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
