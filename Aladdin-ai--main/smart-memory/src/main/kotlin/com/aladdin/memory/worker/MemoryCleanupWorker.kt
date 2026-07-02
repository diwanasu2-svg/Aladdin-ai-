package com.aladdin.memory.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aladdin.memory.repository.MemoryRepository
import com.aladdin.memory.repository.ReminderRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that runs nightly to:
 *   1. Delete expired memories (hard expiry).
 *   2. Forget low-importance memories older than 7 days (LRU).
 *   3. Clean up completed reminders older than 30 days.
 *
 * Schedule: every 24 hours, with 1-hour flex window.
 */
@HiltWorker
class MemoryCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val memoryRepository: MemoryRepository,
    private val reminderRepository: ReminderRepository
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "MemoryCleanupWorker"
        private const val WORK_NAME = "memory_cleanup"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MemoryCleanupWorker>(
                repeatInterval = 24,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
                flexTimeInterval = 1,
                flexTimeIntervalUnit = TimeUnit.HOURS
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.i(TAG, "MemoryCleanupWorker scheduled (24h)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        return try {
            Log.i(TAG, "Starting memory cleanup")

            // 1. Forget expired + stale low-importance memories
            val forgotten = memoryRepository.forget()

            // 2. Clean up completed reminders older than 30 days
            val thirtyDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
            val cleanedReminders = reminderRepository.cleanupCompleted(thirtyDaysAgo)

            Log.i(TAG, "Cleanup complete: forgot=$forgotten cleanedReminders=$cleanedReminders")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup failed: ${e.message}", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
