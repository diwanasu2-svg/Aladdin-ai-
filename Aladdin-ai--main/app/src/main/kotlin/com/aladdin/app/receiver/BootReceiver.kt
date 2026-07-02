package com.aladdin.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.aladdin.app.jarvis.JarvisService

private const val TAG = "BootReceiver"

/**
 * BootReceiver — Task 11: Secure auto-start of JarvisService on device boot.
 *
 * Security hardening:
 *  1. Explicit action whitelist — rejects any action not in the allowed set.
 *  2. No extra-processing: malicious extras from external packages cannot influence startup.
 *  3. Handles LOCKED_BOOT_COMPLETED (direct-boot) separately (CE storage not yet available).
 *  4. Catches SecurityException separately from generic exceptions.
 *
 * Triggers on:
 *  - android.intent.action.BOOT_COMPLETED        (normal boot, requires unlock)
 *  - android.intent.action.LOCKED_BOOT_COMPLETED (direct-boot, before unlock)
 *  - android.intent.action.MY_PACKAGE_REPLACED   (app update self-restart)
 *  - android.intent.action.QUICKBOOT_POWERON     (HTC/Huawei fast-boot)
 *
 * Requires: <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        /** Task 11: Allowed actions — reject anything else (explicit whitelist). */
        private val ALLOWED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action

        // Task 11: Validate action — silently ignore unexpected intents
        if (action == null || action !in ALLOWED_ACTIONS) {
            Log.w(TAG, "Rejected unexpected action: $action")
            return
        }

        Log.i(TAG, "Boot event: $action — starting JarvisService")

        // Task 11: LOCKED_BOOT_COMPLETED fires before Credential-Encrypted storage is
        // available. JarvisService must not access CE-protected data in this path.
        if (action == "android.intent.action.LOCKED_BOOT_COMPLETED"
            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
        ) {
            Log.i(TAG, "Direct-boot mode — CE storage not available yet")
        }

        try {
            JarvisService.start(context)
            Log.i(TAG, "JarvisService started successfully after $action")
        } catch (e: SecurityException) {
            // Task 11: Catch SecurityException separately — may indicate permission revoked
            Log.e(TAG, "SecurityException starting JarvisService on boot: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start JarvisService on boot", e)
        }
    }
}
