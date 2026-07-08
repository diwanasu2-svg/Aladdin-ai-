package com.aladdin.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.aladdin.app.service.AladdinForegroundService

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

        Log.i(TAG, "Boot event: $action — starting AladdinForegroundService")

        // Task 11: LOCKED_BOOT_COMPLETED fires before Credential-Encrypted storage is
        // available. The service must not access CE-protected data in this path.
        if (action == "android.intent.action.LOCKED_BOOT_COMPLETED"
            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
        ) {
            Log.i(TAG, "Direct-boot mode — CE storage not available yet")
        }

        // Reliability fix (2026-07-08): this used to boot the legacy JarvisService,
        // which runs an entirely separate, dead-end pipeline — plain Android TTS with
        // a hardcoded "Yes, how can I help?" reply and no real STT/LLM call at all —
        // while ALSO fighting AladdinForegroundService for exclusive mic access
        // whenever the app was open at the same time. AladdinForegroundService is the
        // one wired to the real JarvisOrchestrator (ONNX wake-word → STT → LLM →
        // TTS), so it's the one that must own 24/7 background listening, including
        // right after every reboot.
        try {
            AladdinForegroundService.start(context)
            Log.i(TAG, "AladdinForegroundService started successfully after $action")
        } catch (e: SecurityException) {
            // Task 11: Catch SecurityException separately — may indicate permission revoked
            Log.e(TAG, "SecurityException starting AladdinForegroundService on boot: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AladdinForegroundService on boot", e)
        }
    }
}
