package com.aladdin.voicecore.service

import android.app.IntentService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aladdin.voicecore.models.DownloadProgress
import com.aladdin.voicecore.models.ModelDownloader
import kotlinx.coroutines.runBlocking

/**
 * Background service to download Voice Core models.
 *
 * Start via:
 * ```kotlin
 * val intent = Intent(context, ModelDownloadService::class.java)
 * context.startService(intent)
 * ```
 *
 * Broadcasts [ACTION_PROGRESS] and [ACTION_DONE] when complete.
 */
class ModelDownloadService : IntentService("ModelDownloadService") {

    companion object {
        private const val TAG = "ModelDownloadService"
        private const val NOTIF_ID = 2001
        private const val CHANNEL_ID = "model_download_channel"

        const val ACTION_PROGRESS = "com.aladdin.voicecore.MODEL_PROGRESS"
        const val ACTION_DONE     = "com.aladdin.voicecore.MODEL_DONE"
        const val ACTION_ERROR    = "com.aladdin.voicecore.MODEL_ERROR"
        const val EXTRA_PROGRESS  = "percent"
        const val EXTRA_MODEL     = "model"
        const val EXTRA_ERROR     = "error"

        fun isReady(context: Context) = ModelDownloader.areAllModelsReady(context)
    }

    override fun onHandleIntent(intent: Intent?) {
        createNotificationChannel()
        showNotification("Downloading Aladdin models…", 0)

        val downloader = ModelDownloader(this)
        runBlocking {
            downloader.downloadAll().collect { progress ->
                when (progress) {
                    is DownloadProgress.Started -> {
                        Log.i(TAG, "Download started – ${progress.total} models")
                    }
                    is DownloadProgress.Downloading -> {
                        val pct = progress.percent
                        val msg = "Downloading ${progress.name} (${progress.index}/${progress.total}) – $pct%"
                        Log.d(TAG, msg)
                        showNotification(msg, pct)
                        sendBroadcast(Intent(ACTION_PROGRESS).apply {
                            putExtra(EXTRA_PROGRESS, pct)
                            putExtra(EXTRA_MODEL, progress.name)
                        })
                    }
                    is DownloadProgress.Extracting -> {
                        showNotification("Extracting ${progress.name}…", -1)
                    }
                    is DownloadProgress.Done -> {
                        Log.i(TAG, "All models ready")
                        showNotification("Aladdin models ready!", 100)
                        sendBroadcast(Intent(ACTION_DONE))
                    }
                    is DownloadProgress.Error -> {
                        Log.e(TAG, "Download error for ${progress.modelId}: ${progress.message}")
                        showNotification("Download error: ${progress.message}", -1)
                        sendBroadcast(Intent(ACTION_ERROR).apply {
                            putExtra(EXTRA_MODEL, progress.modelId)
                            putExtra(EXTRA_ERROR, progress.message)
                        })
                    }
                }
            }
        }
    }

    private fun showNotification(text: String, progress: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Aladdin Voice Core Setup")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)

        if (progress in 0..100) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, true) // indeterminate
        }

        nm.notify(NOTIF_ID, builder.build())
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model Downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setSound(null, null)
            enableVibration(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}
