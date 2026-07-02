package com.aladdin.app.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PermissionManager"

/**
 * PermissionManager — central authority for checking and categorizing permissions.
 *
 * Provides:
 * - [getMissingPermissions]  — list of permissions that still need to be granted
 * - [isGranted]              — single-permission check
 * - [getRationale]           — user-friendly explanation for each permission
 * - [REQUIRED_PERMISSIONS]   — full list of permissions the app needs
 */
@Singleton
class PermissionManager @Inject constructor() {

    // ─── Permission groups ────────────────────────────────────────────────────

    val CORE_PERMISSIONS: List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    val OPTIONAL_PERMISSIONS: List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
    }

    val REQUIRED_PERMISSIONS: List<String> = CORE_PERMISSIONS + OPTIONAL_PERMISSIONS

    // ─── Checks ───────────────────────────────────────────────────────────────

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun getMissingPermissions(context: Context): List<String> =
        CORE_PERMISSIONS.filter { !isGranted(context, it) }.also { missing ->
            if (missing.isNotEmpty()) Log.d(TAG, "Missing: ${missing.joinToString()}")
        }

    fun getMissingOptionalPermissions(context: Context): List<String> =
        OPTIONAL_PERMISSIONS.filter { !isGranted(context, it) }

    fun allCoreGranted(context: Context): Boolean =
        getMissingPermissions(context).isEmpty()

    fun hasMicrophonePermission(context: Context): Boolean =
        isGranted(context, Manifest.permission.RECORD_AUDIO)

    fun hasCameraPermission(context: Context): Boolean =
        isGranted(context, Manifest.permission.CAMERA)

    fun hasNotificationPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
        else true

    fun hasStoragePermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            isGranted(context, Manifest.permission.READ_MEDIA_IMAGES)
        else isGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)

    fun hasLocationPermission(context: Context): Boolean =
        isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    // ─── Rationale strings ────────────────────────────────────────────────────

    fun getRationale(permission: String): String = when (permission) {
        Manifest.permission.RECORD_AUDIO          -> "Aladdin needs the microphone to hear your voice commands."
        Manifest.permission.CAMERA                -> "Aladdin needs the camera for visual analysis and document scanning."
        Manifest.permission.POST_NOTIFICATIONS    -> "Aladdin sends alerts and reminders via notifications."
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO      -> "Aladdin needs storage access to save and read files."
        Manifest.permission.ACCESS_FINE_LOCATION  -> "Aladdin uses your location for location-aware reminders."
        Manifest.permission.READ_CONTACTS         -> "Aladdin reads your contacts to help with calls and messages."
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR        -> "Aladdin reads and creates calendar events for you."
        Manifest.permission.BLUETOOTH_CONNECT     -> "Aladdin connects to Bluetooth devices for audio output."
        else -> "This permission is required for Aladdin to function correctly."
    }

    fun getPermissionSummary(context: Context): Map<String, Boolean> =
        REQUIRED_PERMISSIONS.associateWith { isGranted(context, it) }
}
