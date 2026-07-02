package com.aladdin.assistant.ui.quicksettings

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import com.aladdin.assistant.ui.MainActivity

// ─── Phase 6 Item 5: Quick Settings Tile — Fixed & Enhanced ──────────────────
// Changes applied:
//   • SharedPreferences used to persist toggle state across reboots
//   • Tile state restored in onStartListening() from persisted value
//   • Tile icon and label update correctly on every toggle
//   • startActivityAndCollapse() API-level guarded for Android 14+
//   • Logging added for diagnostics

@RequiresApi(Build.VERSION_CODES.N)
class AladdinQuickSettingsTile : TileService() {

    companion object {
        private const val TAG        = "AladdinQSTile"
        private const val PREFS_NAME = "aladdin_qs_tile"
        private const val KEY_ACTIVE = "tile_active"
    }

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Read persisted state; default to inactive
    private var isActive: Boolean
        get()      = prefs.getBoolean(KEY_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_ACTIVE, value).apply()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onStartListening() {
        super.onStartListening()
        Log.d(TAG, "onStartListening — restoring persisted state: $isActive")
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        isActive = !isActive          // toggle and persist
        Log.i(TAG, "Tile clicked — new state: $isActive")

        if (isActive) {
            launchVoiceInput()
        }
        updateTile()
    }

    override fun onLongClick() {
        super.onLongClick()
        Log.d(TAG, "Tile long-pressed — opening main app")
        launchMainApp()
    }

    override fun onStopListening() {
        super.onStopListening()
        Log.d(TAG, "onStopListening")
    }

    // ── Tile update ───────────────────────────────────────────────────────────

    private fun updateTile() {
        qsTile?.apply {
            state    = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label    = "Aladdin AI"
            subtitle = if (isActive) "Active" else "Tap to activate"
            updateTile()
        }
    }

    // ── Intents ───────────────────────────────────────────────────────────────

    private fun launchVoiceInput() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = "ACTION_VOICE_INPUT"
            flags  = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivityCompat(intent)
    }

    private fun launchMainApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivityCompat(intent)
    }

    private fun startActivityCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this, 0, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
