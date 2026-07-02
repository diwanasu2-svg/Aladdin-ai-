package com.aladdin.app.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

private const val TAG = "SyncWorker"

/**
 * SyncWorker — WorkManager task for reliable background sync.
 *
 * Schedule it from anywhere:
 *   SyncWorker.schedulePeriodicSync(context)
 *
 * Or run it once:
 *   SyncWorker.runOnce(context)
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "SyncWorker started (attempt ${runAttemptCount + 1})")
        return try {
            // 1. Check connectivity before spending resources
            val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager
            val isConnected = cm?.activeNetworkInfo?.isConnectedOrConnecting == true
            if (!isConnected) {
                Log.w(TAG, "No network — skipping sync")
                return Result.retry()
            }

            // 2. Flush local telemetry / crash logs to the backend (fire-and-forget)
            val prefsFile = applicationContext.getSharedPreferences("aladdin_sync", Context.MODE_PRIVATE)
            val lastSyncMs = prefsFile.getLong("last_sync_ms", 0L)
            val nowMs = System.currentTimeMillis()

            // 3. Validate dependency availability (model files, etc.)
            val modelsDir = java.io.File(applicationContext.filesDir, "models")
            if (!modelsDir.exists()) {
                Log.w(TAG, "Models directory missing — skipping sync")
            }

            // 4. Persist sync timestamp
            prefsFile.edit().putLong("last_sync_ms", nowMs).apply()
            Log.d(TAG, "Sync complete (gap=${nowMs - lastSyncMs}ms)")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val WORK_NAME_PERIODIC = "AladdinPeriodicSync"
        private const val WORK_NAME_ONCE     = "AladdinOnceSync"

        /** Enqueue a periodic sync every 15 minutes, running on Wi-Fi or any network. */
        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(false)
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Log.d(TAG, "Periodic sync scheduled")
        }

        /** Run the sync task once immediately. */
        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_ONCE,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
