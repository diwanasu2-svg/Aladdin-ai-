package com.aladdin.app.messaging.fcm

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aladdin.app.MainActivity
import com.aladdin.app.messaging.MessagingRepository
import com.aladdin.app.messaging.models.Message
import com.aladdin.app.messaging.models.Platform
import com.aladdin.app.notification.NotificationHelper
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "AladdinFcmService"
private const val FCM_NOTIF_ID_BASE = 5000

/**
 * AladdinFcmService — Firebase Cloud Messaging receiver.
 *
 * Handles two message types:
 *  - **Notification messages** — displayed automatically by FCM (app background)
 *  - **Data messages** — routed through [MessagingRepository] (foreground / background)
 *
 * Expected data payload keys:
 *   "type"    → "command" | "message" | "alert"
 *   "text"    → message body
 *   "chatId"  → origin identifier
 *   "sender"  → sender name
 *
 * Declare in AndroidManifest inside <application>:
 *   <service android:name=".messaging.fcm.AladdinFcmService"
 *            android:exported="false">
 *     <intent-filter>
 *       <action android:name="com.google.firebase.MESSAGING_EVENT"/>
 *     </intent-filter>
 *   </service>
 */
@AndroidEntryPoint
class AladdinFcmService : FirebaseMessagingService() {

    @Inject lateinit var repository: MessagingRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ─── New token ────────────────────────────────────────────────────────────

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM token refreshed: ${token.take(20)}…")

        // Persist token to SharedPreferences for retrieval by backend sync
        getSharedPreferences("aladdin_fcm", Context.MODE_PRIVATE)
            .edit()
            .putString("fcm_token", token)
            .putLong("fcm_token_ts", System.currentTimeMillis())
            .apply()

        // Subscribe this device to the Aladdin command topic so it can receive
        // push-initiated commands without requiring the token to be uploaded.
        subscribeToTopic("aladdin_commands")

        Log.i(TAG, "FCM token persisted and topic subscribed")
    }

    // ─── Incoming push ────────────────────────────────────────────────────────

    override fun onMessageReceived(remote: RemoteMessage) {
        super.onMessageReceived(remote)
        Log.d(TAG, "FCM message from ${remote.from}")

        val data = remote.data
        val type = data["type"] ?: "message"

        when (type) {
            "command" -> handleCommand(data)
            "alert"   -> showHighPriorityNotification(
                title = data["title"] ?: "Aladdin Alert",
                body  = data["text"] ?: ""
            )
            else -> {
                val text     = data["text"] ?: remote.notification?.body ?: return
                val chatId   = data["chatId"] ?: remote.from ?: "fcm"
                val sender   = data["sender"] ?: "FCM"
                val message  = Message(
                    id = remote.messageId ?: System.currentTimeMillis().toString(),
                    platform = Platform.FCM,
                    chatId = chatId,
                    senderId = sender,
                    senderName = sender,
                    text = text
                )
                scope.launch { repository.onIncomingMessage(message) }
                showMessageNotification(sender, text)
            }
        }
    }

    // ─── Command handling ─────────────────────────────────────────────────────

    private fun handleCommand(data: Map<String, String>) {
        val cmd = data["command"] ?: return
        Log.d(TAG, "FCM command: $cmd")
        // Extend with your app-specific commands (e.g. "start_jarvis", "read_emails")
    }

    // ─── Notifications ────────────────────────────────────────────────────────

    private fun showMessageNotification(sender: String, text: String) {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_FOREGROUND)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Message from $sender")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(FCM_NOTIF_ID_BASE + sender.hashCode(), notif)
    }

    private fun showHighPriorityNotification(title: String, body: String) {
        val notif = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_FOREGROUND)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(FCM_NOTIF_ID_BASE, notif)
    }

    companion object {
        /** Subscribe the device to a topic (e.g. "aladdin_commands"). */
        fun subscribeToTopic(topic: String) {
            FirebaseMessaging.getInstance().subscribeToTopic(topic)
                .addOnCompleteListener { task ->
                    Log.d(TAG, if (task.isSuccessful) "Subscribed to $topic" else "Subscribe failed: ${task.exception}")
                }
        }

        /** Get current FCM registration token. */
        fun getToken(callback: (String?) -> Unit) {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                callback(if (task.isSuccessful) task.result else null)
            }
        }
    }
}
