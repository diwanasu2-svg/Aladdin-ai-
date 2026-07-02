package com.aladdin.app.intelligence

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// ─── Phase 11 Item 1: WorkManager Reminders (replaces mock reminders) ─────────

@Singleton
class WorkManagerReminders @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "WorkManagerReminders"
        const val CHANNEL_ID  = "aladdin_reminders"
        const val KEY_TITLE   = "title"
        const val KEY_BODY    = "body"
        const val KEY_ID      = "notification_id"
    }

    private val workManager = WorkManager.getInstance(context)

    // ── Schedule one-off reminder ─────────────────────────────────────────────
    fun scheduleOnce(
        id: String,
        title: String,
        body: String,
        delayMs: Long,
        notificationId: Int = id.hashCode()
    ): Operation {
        val data = workDataOf(KEY_TITLE to title, KEY_BODY to body, KEY_ID to notificationId)
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(data)
            .addTag(id)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        Log.i(TAG, "Scheduling reminder '$id' in ${delayMs / 1000}s")
        return workManager.enqueueUniqueWork(id, ExistingWorkPolicy.REPLACE, req)
    }

    // ── Schedule repeating reminder ───────────────────────────────────────────
    fun scheduleRepeating(
        id: String,
        title: String,
        body: String,
        intervalMs: Long,
        notificationId: Int = id.hashCode()
    ): Operation {
        val data = workDataOf(KEY_TITLE to title, KEY_BODY to body, KEY_ID to notificationId)
        val intervalMin = (intervalMs / 60_000).coerceAtLeast(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS / 60_000)
        val req = PeriodicWorkRequestBuilder<ReminderWorker>(intervalMin, TimeUnit.MINUTES)
            .setInputData(data)
            .addTag(id)
            .build()
        Log.i(TAG, "Scheduling repeating reminder '$id' every ${intervalMin}min")
        return workManager.enqueueUniquePeriodicWork(id, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    // ── Schedule location-based reminder via monitoring ───────────────────────
    fun scheduleLocationBased(
        id: String,
        title: String,
        body: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Float = 200f,
        notificationId: Int = id.hashCode()
    ): Operation {
        // Uses a polling worker that checks current location vs target
        val data = workDataOf(
            KEY_TITLE to title, KEY_BODY to body, KEY_ID to notificationId,
            "lat" to latitude, "lng" to longitude, "radius" to radiusMeters
        )
        val req = PeriodicWorkRequestBuilder<LocationReminderWorker>(15, TimeUnit.MINUTES)
            .setInputData(data)
            .addTag(id)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build()
            )
            .build()
        Log.i(TAG, "Scheduling location reminder '$id' at (${latitude},${longitude}) r=${radiusMeters}m")
        return workManager.enqueueUniquePeriodicWork(id, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun cancel(id: String) {
        workManager.cancelUniqueWork(id)
        Log.i(TAG, "Cancelled reminder '$id'")
    }

    fun cancelAll() {
        workManager.cancelAllWork()
        Log.i(TAG, "Cancelled all reminders")
    }

    fun getStatus(id: String) = workManager.getWorkInfosForUniqueWorkLiveData(id)
}

// ─── One-off reminder worker ──────────────────────────────────────────────────
class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val title = inputData.getString(WorkManagerReminders.KEY_TITLE) ?: "Reminder"
        val body  = inputData.getString(WorkManagerReminders.KEY_BODY)  ?: ""
        val notId = inputData.getInt(WorkManagerReminders.KEY_ID, System.currentTimeMillis().toInt())
        return try {
            showNotification(applicationContext, title, body, notId)
            Log.i("ReminderWorker", "Fired reminder: $title")
            Result.success()
        } catch (e: Exception) {
            Log.e("ReminderWorker", "Failed: ${e.message}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

// ─── Location-based reminder worker ──────────────────────────────────────────
class LocationReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val lat    = inputData.getDouble("lat", 0.0)
        val lng    = inputData.getDouble("lng", 0.0)
        val radius = inputData.getFloat("radius", 200f)
        val title  = inputData.getString(WorkManagerReminders.KEY_TITLE) ?: "Location Reminder"
        val body   = inputData.getString(WorkManagerReminders.KEY_BODY)  ?: ""
        val notId  = inputData.getInt(WorkManagerReminders.KEY_ID, System.currentTimeMillis().toInt())

        return try {
            val loc = getLastKnownLocation(applicationContext)
            if (loc != null) {
                val results = FloatArray(1)
                android.location.Location.distanceBetween(loc.first, loc.second, lat, lng, results)
                if (results[0] <= radius) {
                    showNotification(applicationContext, title, body, notId)
                    Log.i("LocationReminderWorker", "Location match — fired: $title")
                }
            }
            Result.success()
        } catch (e: Exception) {
            Log.e("LocationReminderWorker", "Error: ${e.message}")
            Result.retry()
        }
    }

    private fun getLastKnownLocation(ctx: Context): Pair<Double, Double>? {
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            loc?.let { Pair(it.latitude, it.longitude) }
        } catch (e: SecurityException) {
            Log.w("LocationReminderWorker", "Location permission denied")
            null
        }
    }
}

private fun showNotification(ctx: Context, title: String, body: String, notId: Int) {
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        val ch = android.app.NotificationChannel(
            WorkManagerReminders.CHANNEL_ID, "Aladdin Reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Scheduled reminders from Aladdin AI" }
        nm.createNotificationChannel(ch)
    }
    val n = NotificationCompat.Builder(ctx, WorkManagerReminders.CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(body)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()
    nm.notify(notId, n)
}
