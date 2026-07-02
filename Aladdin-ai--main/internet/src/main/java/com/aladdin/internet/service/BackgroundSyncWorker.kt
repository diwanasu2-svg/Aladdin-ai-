package com.aladdin.internet.service

import android.content.Context
import androidx.work.*
import com.aladdin.internet.cache.SearchCacheDatabase
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that runs periodically in the background to:
 *  1. Purge expired Room cache entries.
 *  2. Pre-warm frequently used queries (if provided).
 */
class BackgroundSyncWorker(
    context: Context,
    params:  WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val db  = SearchCacheDatabase.getInstance(applicationContext)
            val dao = db.searchCacheDao()

            // Remove expired entries
            dao.deleteExpired()

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "aladdin_internet_cache_sync"

        /** Schedule a periodic 6-hour background cleanup. */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
        }

        /** Run an immediate one-time sync. */
        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<BackgroundSyncWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
