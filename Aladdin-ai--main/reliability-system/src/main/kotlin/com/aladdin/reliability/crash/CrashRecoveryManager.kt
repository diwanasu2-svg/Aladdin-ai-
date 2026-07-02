package com.aladdin.reliability.crash

import android.content.Context
import android.util.Log

/**
 * Manages post-crash state restore and recovery attempt tracking.
 */
class CrashRecoveryManager(private val context: Context) {

    companion object {
        private const val TAG = "CrashRecoveryManager"
        private const val PREFS = "aladdin_recovery_state"
        private const val KEY_STATE = "saved_state"
        private const val KEY_PENDING_RECOVERY = "pending_recovery"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Save arbitrary state JSON before a risky operation */
    fun saveState(stateJson: String) {
        prefs.edit().putString(KEY_STATE, stateJson).apply()
    }

    /** Returns saved state if a recovery is pending, null otherwise */
    fun getPendingRecoveryState(): String? {
        if (!prefs.getBoolean(KEY_PENDING_RECOVERY, false)) return null
        return prefs.getString(KEY_STATE, null)
    }

    /** Mark that a crash occurred and recovery state is available */
    fun markCrashOccurred() {
        prefs.edit().putBoolean(KEY_PENDING_RECOVERY, true).apply()
    }

    /** Call once state has been successfully restored */
    fun clearRecovery() {
        prefs.edit()
            .remove(KEY_PENDING_RECOVERY)
            .remove(KEY_STATE)
            .apply()
        Log.i(TAG, "Recovery state cleared")
    }

    /** Check whether we're coming back from a crash */
    fun isRecovering(): Boolean = prefs.getBoolean(KEY_PENDING_RECOVERY, false)

    /** Number of consecutive crash-restarts tracked by CrashHandler */
    fun getRecoveryAttempts(): Int =
        context.getSharedPreferences(CrashHandler.PREF_CRASH_RECOVERY, Context.MODE_PRIVATE)
            .getInt(CrashHandler.KEY_RECOVERY_ATTEMPTS, 0)

    /** Reset recovery attempt counter after a clean run */
    fun resetAttemptCounter() {
        context.getSharedPreferences(CrashHandler.PREF_CRASH_RECOVERY, Context.MODE_PRIVATE)
            .edit().putInt(CrashHandler.KEY_RECOVERY_ATTEMPTS, 0).apply()
    }
}
