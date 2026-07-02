package com.aladdin.reliability.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.media.AudioFormat
import android.media.MediaRecorder
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat

class ComponentValidator(private val context: Context) {

    fun validateAll(): List<DiagnosticResult> = listOf(
        validate("Room DB") { validateRoomDb() },
        validate("SharedPreferences") { validateSharedPrefs() },
        validate("External Storage") { validateExternalStorage() },
        validate("AudioRecord API") { validateAudioRecord() },
        validate("Camera API") { validateCamera() },
        validate("Classloader") { validateClassLoader() },
        validate("Coroutines") { validateCoroutines() }
    )

    private inline fun validate(name: String, block: () -> Pair<DiagnosticStatus, String>): DiagnosticResult {
        val t0 = System.currentTimeMillis()
        return try {
            val (status, msg) = block()
            DiagnosticResult(name, status, msg, System.currentTimeMillis() - t0)
        } catch (e: Exception) {
            DiagnosticResult(name, DiagnosticStatus.FAIL, "Exception: ${e.message}", System.currentTimeMillis() - t0)
        }
    }

    private fun validateRoomDb(): Pair<DiagnosticStatus, String> {
        val db = context.openOrCreateDatabase("diag_test.db", Context.MODE_PRIVATE, null)
        db.execSQL("CREATE TABLE IF NOT EXISTS t(id INTEGER PRIMARY KEY)")
        db.execSQL("INSERT INTO t VALUES(1)")
        val cur = db.rawQuery("SELECT * FROM t", null)
        val ok = cur.count > 0
        cur.close(); db.close()
        context.deleteDatabase("diag_test.db")
        return if (ok) Pair(DiagnosticStatus.PASS, "SQLite read/write OK")
        else Pair(DiagnosticStatus.FAIL, "SQLite read returned no rows")
    }

    private fun validateSharedPrefs(): Pair<DiagnosticStatus, String> {
        val prefs = context.getSharedPreferences("diag_test", Context.MODE_PRIVATE)
        prefs.edit().putString("key", "val").commit()
        val ok = prefs.getString("key", null) == "val"
        prefs.edit().clear().apply()
        return if (ok) Pair(DiagnosticStatus.PASS, "SharedPreferences read/write OK")
        else Pair(DiagnosticStatus.FAIL, "SharedPreferences write/read mismatch")
    }

    private fun validateExternalStorage(): Pair<DiagnosticStatus, String> {
        val state = Environment.getExternalStorageState()
        return if (state == Environment.MEDIA_MOUNTED)
            Pair(DiagnosticStatus.PASS, "External storage mounted")
        else Pair(DiagnosticStatus.WARN, "External storage state: $state")
    }

    private fun validateAudioRecord(): Pair<DiagnosticStatus, String> {
        val hasPerm = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return Pair(DiagnosticStatus.WARN, "RECORD_AUDIO permission not granted")
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return if (minBuf > 0) Pair(DiagnosticStatus.PASS, "AudioRecord supported (minBuf=$minBuf)")
        else Pair(DiagnosticStatus.FAIL, "AudioRecord.getMinBufferSize returned $minBuf")
    }

    private fun validateCamera(): Pair<DiagnosticStatus, String> {
        val hasPerm = ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        return if (hasPerm) Pair(DiagnosticStatus.PASS, "CAMERA permission granted")
        else Pair(DiagnosticStatus.WARN, "CAMERA permission not granted")
    }

    private fun validateClassLoader(): Pair<DiagnosticStatus, String> {
        val cl = context.classLoader
        return if (cl != null) Pair(DiagnosticStatus.PASS, "ClassLoader available: ${cl.javaClass.simpleName}")
        else Pair(DiagnosticStatus.FAIL, "ClassLoader is null")
    }

    private fun validateCoroutines(): Pair<DiagnosticStatus, String> {
        var ok = false
        kotlinx.coroutines.runBlocking { ok = true }
        return if (ok) Pair(DiagnosticStatus.PASS, "Coroutines runtime OK")
        else Pair(DiagnosticStatus.FAIL, "Coroutines runBlocking failed")
    }
}
