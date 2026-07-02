package com.aladdin.performance.startup

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Orchestrates the complete < 3s startup sequence:
 *
 *  T+0ms   Application.onCreate() starts
 *  T+0ms   Splash screen installed
 *  T+0ms   Critical services initialised (voice, AI engine core)
 *  T+~800ms  Activity created, splash dismissed
 *  T+~1000ms Deferred services start in background (analytics, health checks…)
 *  T+<3000ms App fully ready
 */
class StartupOptimizer(private val context: Context) {

    companion object { private const val TAG = "StartupOptimizer" }

    val tracker    = StartupTracker
    val initializer = LazyServiceInitializer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var splashKeepVisible = true

    /** Call from Application.onCreate() */
    suspend fun onApplicationCreate() {
        tracker.begin()
        tracker.mark("Application.onCreate")

        tracker.mark("CriticalInit.start")
        initializer.initCritical()
        tracker.mark("CriticalInit.done")
    }

    /**
     * Call from your splash/main Activity.onCreate().
     * Pass [activity] so the splash screen can be installed on it.
     */
    fun onActivityCreate(activity: Activity) {
        val splash = activity.installSplashScreen()
        splash.setKeepOnScreenCondition { splashKeepVisible }

        scope.launch {
            tracker.mark("Activity.onCreate")
            // Dismiss splash as soon as critical init is done
            splashKeepVisible = false
            tracker.mark("SplashDismissed")

            // Then kick off deferred services (1 second after splash dismissed)
            initializer.initDeferred(delayMs = 1_000L)

            val total = tracker.finish("AppReady")
            Log.i(TAG, tracker.report())
            if (!tracker.metTarget()) {
                Log.w(TAG, "Performance target missed! ${total}ms > ${StartupTracker.TARGET_MS}ms")
            }
        }
    }

    fun registerCriticalService(name: String, init: suspend () -> Unit) =
        initializer.register(name, critical = true, init = init)

    fun registerDeferredService(name: String, init: suspend () -> Unit) =
        initializer.register(name, critical = false, init = init)
}
