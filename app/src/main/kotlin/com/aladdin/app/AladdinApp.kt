package com.aladdin.app

import android.app.Application
import android.os.Build
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

    init {
        // Installed in the constructor / init block so it is active before
        // Hilt's generated super.onCreate() runs (Hilt performs dependency
        // injection there, and a binding/injection failure would otherwise
        // crash the process before our logger got a chance to attach).
        installCrashLogger()
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Aladdin starting")

        initWorkManager()
        initNotificationChannels()
        initFirebase()
        initReliability()
        initModelDownloader()
    }

    // ── Crash logging (diagnostic aid — writes full stack trace to Downloads) ──
    //
    // Purpose: capture the exact reason the app dies on launch/at runtime, so it
    // can be diagnosed without needing adb/logcat access on the device.
    private fun installCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(thread, throwable)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write crash log: ${e.message}")
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun writeCrashLog(thread: Thread, throwable: Throwable) {
        val sw = java.io.StringWriter()
        throwable.printStackTrace(java.io.PrintWriter(sw))
        val timestamp = java.text.SimpleDateFormat(
            "yyyy-MM-dd_HH-mm-ss", java.util.Locale.US
        ).format(java.util.Date())
        val content = buildString {
            appendLine("Aladdin crash report")
            appendLine("Time: $timestamp")
            appendLine("Thread: ${thread.name}")
            appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            append(sw.toString())
        }
        val fileName = "Aladdin_crash_$timestamp.txt"

        // Public Downloads folder (visible in any file manager) — API 29+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { os ->
                        os.write(content.toByteArray())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "MediaStore crash log write failed: ${e.message}")
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                downloadsDir.mkdirs()
                java.io.File(downloadsDir, fileName).writeText(content)
            } catch (e: Exception) {
                Log.e(TAG, "Legacy crash log write failed: ${e.message}")
            }
        }

        // Always also write to app-private storage as a reliable fallback
        // (no permissions needed, but only browsable from within the app / adb).
        try {
            val privateDir = getExternalFilesDir(null) ?: filesDir
            java.io.File(privateDir, fileName).writeText(content)
        } catch (e: Exception) {
            Log.e(TAG, "Private crash log write failed: ${e.message}")
        }
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
