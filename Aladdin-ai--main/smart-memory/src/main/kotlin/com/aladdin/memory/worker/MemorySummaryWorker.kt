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
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that auto-summarizes conversation memories daily.
 *
 * Runs every 24 hours, summarizing all conversation turns older than 24h
 * that have not yet been summarized. Compressed summaries replace the
 * original turns in the LLM context window.
 */
@HiltWorker
class MemorySummaryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val memoryRepository: MemoryRepository
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "MemorySummaryWorker"
        private const val WORK_NAME = "memory_summary"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MemorySummaryWorker>(
                repeatInterval = 24,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
                flexTimeInterval = 2,
                flexTimeIntervalUnit = TimeUnit.HOURS
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.i(TAG, "MemorySummaryWorker scheduled (24h)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        return try {
            Log.i(TAG, "Starting memory summarization")
            val beforeMs = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24)
            val count = memoryRepository.summarizeOldMemories(beforeMs)
            Log.i(TAG, "Summarization complete: $count memories processed")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Summarization failed: ${e.message}", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
