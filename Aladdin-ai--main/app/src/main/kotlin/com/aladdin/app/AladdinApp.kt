package com.aladdin.app

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager
import com.aladdin.app.notification.NotificationHelper
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

private const val TAG = "AladdinApp"

/**
 * Phase 1 fix item 1.3 — Firebase Analytics, FCM, and Crashlytics initialized here.
 * Phase 1 fix item 1.5 — ModelDownloader wired to onCreate().
 */
@HiltAndroidApp
class AladdinApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: androidx.hilt.work.HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Aladdin starting")

        initWorkManager()
        initNotificationChannels()
        initFirebase()
        initReliability()
        initModelDownloader()
    }

    // ── WorkManager ───────────────────────────────────────────────────────────

    private fun initWorkManager() {
        WorkManager.initialize(this, workManagerConfiguration)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun initNotificationChannels() {
        NotificationHelper.createAllChannels(this)
    }

    // ── Firebase — Phase 1 fix item 1.3 ──────────────────────────────────────

    private fun initFirebase() {
        try {
            // Firebase Analytics
            com.google.firebase.FirebaseApp.initializeApp(this)
            Log.i(TAG, "Firebase initialized")

            // Crashlytics — auto-crash reporting
            try {
                com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
                    .setCrashlyticsCollectionEnabled(true)
                Log.i(TAG, "Crashlytics enabled")
            } catch (e: Exception) {
                Log.w(TAG, "Crashlytics not available: ${e.message}")
            }

            // FCM — token refresh handled by AladdinFcmService
            try {
                com.google.firebase.messaging.FirebaseMessaging.getInstance()
                    .isAutoInitEnabled = true
                Log.i(TAG, "FCM auto-init enabled")
            } catch (e: Exception) {
                Log.w(TAG, "FCM not available: ${e.message}")
            }

        } catch (e: Exception) {
            Log.w(TAG, "Firebase init failed (replace google-services.json with real config): ${e.message}")
        }
    }

    // ── Model Downloader — Phase 1 fix item 1.6 ──────────────────────────────

    private fun initReliability() {
        try {
            val reliability = com.aladdin.reliability.manager.ReliabilityManager(
                context = this,
                appVersion = com.aladdin.app.BuildConfig.VERSION_NAME
            )
            reliability.start(restartIntent = packageManager.getLaunchIntentForPackage(packageName))
            Log.i(TAG, "ReliabilityManager started")
        } catch (e: Exception) {
            Log.w(TAG, "ReliabilityManager init failed: ${e.message}")
        }
    }

    private fun initModelDownloader() {
        try {
            val downloader = com.aladdin.app.download.ModelDownloaderHelper(this)
            downloader.ensureModelsPresent()
            Log.i(TAG, "Model downloader initialized")
        } catch (e: Exception) {
            Log.w(TAG, "Model downloader init failed: ${e.message}")
        }
    }
}
