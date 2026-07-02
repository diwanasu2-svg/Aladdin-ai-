package com.aladdin.app.backup

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

// ─── Phase 13 Item 2: Restore System — DB, settings, selective restore ─────────

@Singleton
class RestoreManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG       = "RestoreManager"
        private const val BACKUP_DIR = "backups"
    }

    enum class RestoreMode { FULL, SETTINGS_ONLY, DATABASE_ONLY, MEMORIES_ONLY }

    data class RestoreResult(
        val success: Boolean,
        val restoredItems: List<String>,
        val skippedItems: List<String>,
        val errorMessage: String? = null
    )

    data class BackupInfo(
        val fileName: String,
        val createdAt: Long,
        val version: String,
        val itemCount: Int,
        val sizeBytes: Long
    )

    // ── List available backups ────────────────────────────────────────────────
    fun listBackups(): List<BackupInfo> {
        val backupDir = File(context.filesDir, BACKUP_DIR)
        if (!backupDir.exists()) return emptyList()

        return backupDir.listFiles { f -> f.extension == "zip" }
            ?.map { file ->
                try {
                    val meta = readBackupMeta(file)
                    BackupInfo(
                        fileName   = file.name,
                        createdAt  = meta.optLong("timestamp", file.lastModified()),
                        version    = meta.optString("version", "unknown"),
                        itemCount  = meta.optInt("itemCount", 0),
                        sizeBytes  = file.length()
                    )
                } catch (e: Exception) {
                    BackupInfo(file.name, file.lastModified(), "unknown", 0, file.length())
                }
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    // ── Full restore from backup file ─────────────────────────────────────────
    suspend fun restore(
        backupFileName: String,
        mode: RestoreMode = RestoreMode.FULL,
        onProgress: ((Int, String) -> Unit)? = null
    ): RestoreResult = withContext(Dispatchers.IO) {
        val restored = mutableListOf<String>()
        val skipped  = mutableListOf<String>()

        try {
            val backupFile = File(context.filesDir, "$BACKUP_DIR/$backupFileName")
            if (!backupFile.exists()) {
                return@withContext RestoreResult(false, emptyList(), emptyList(), "Backup file not found: $backupFileName")
            }

            Log.i(TAG, "Starting $mode restore from $backupFileName (${backupFile.length() / 1024}KB)")
            onProgress?.invoke(0, "Opening backup…")

            ZipFile(backupFile).use { zip ->
                val entries = zip.entries().toList()
                entries.forEachIndexed { i, entry ->
                    val progress = ((i + 1) * 100) / entries.size

                    when {
                        entry.name == "meta.json" -> {
                            // Already handled
                        }

                        entry.name == "settings.json" && mode in listOf(RestoreMode.FULL, RestoreMode.SETTINGS_ONLY) -> {
                            onProgress?.invoke(progress, "Restoring settings…")
                            val json = zip.getInputStream(entry).bufferedReader().readText()
                            restoreSettings(JSONObject(json))
                            restored.add("settings.json")
                            Log.i(TAG, "Settings restored")
                        }

                        entry.name.startsWith("db/") && mode in listOf(RestoreMode.FULL, RestoreMode.DATABASE_ONLY) -> {
                            onProgress?.invoke(progress, "Restoring database: ${entry.name}…")
                            val dbName = entry.name.removePrefix("db/")
                            val data   = zip.getInputStream(entry).readBytes()
                            restoreDatabase(dbName, data)
                            restored.add(entry.name)
                        }

                        entry.name.startsWith("memories/") && mode in listOf(RestoreMode.FULL, RestoreMode.MEMORIES_ONLY) -> {
                            onProgress?.invoke(progress, "Restoring memories…")
                            val data = zip.getInputStream(entry).readBytes()
                            restoreMemoryFile(entry.name.removePrefix("memories/"), data)
                            restored.add(entry.name)
                        }

                        else -> {
                            if (mode != RestoreMode.FULL) {
                                skipped.add(entry.name)
                            } else {
                                onProgress?.invoke(progress, "Restoring ${entry.name}…")
                                val data   = zip.getInputStream(entry).readBytes()
                                val target = File(context.filesDir, entry.name)
                                target.parentFile?.mkdirs()
                                target.writeBytes(data)
                                restored.add(entry.name)
                            }
                        }
                    }
                }
            }

            onProgress?.invoke(100, "Restore complete")
            Log.i(TAG, "Restore done: ${restored.size} restored, ${skipped.size} skipped")
            RestoreResult(true, restored, skipped)

        } catch (e: Exception) {
            Log.e(TAG, "Restore failed: ${e.message}")
            RestoreResult(false, restored, skipped, e.message)
        }
    }

    // ── Selective restore — restore only specific items ───────────────────────
    suspend fun restoreSelective(
        backupFileName: String,
        itemNames: List<String>
    ): RestoreResult = withContext(Dispatchers.IO) {
        val restored = mutableListOf<String>()
        val skipped  = mutableListOf<String>()

        try {
            val backupFile = File(context.filesDir, "$BACKUP_DIR/$backupFileName")
            if (!backupFile.exists()) {
                return@withContext RestoreResult(false, emptyList(), emptyList(), "Backup not found")
            }

            ZipFile(backupFile).use { zip ->
                itemNames.forEach { name ->
                    val entry = zip.getEntry(name)
                    if (entry != null) {
                        val data   = zip.getInputStream(entry).readBytes()
                        val target = File(context.filesDir, name)
                        target.parentFile?.mkdirs()
                        target.writeBytes(data)
                        restored.add(name)
                        Log.i(TAG, "Selectively restored: $name")
                    } else {
                        skipped.add(name)
                        Log.w(TAG, "Item not in backup: $name")
                    }
                }
            }

            RestoreResult(true, restored, skipped)
        } catch (e: Exception) {
            Log.e(TAG, "selectiveRestore failed: ${e.message}")
            RestoreResult(false, restored, skipped, e.message)
        }
    }

    // ── Verify backup integrity ───────────────────────────────────────────────
    suspend fun verifyBackup(backupFileName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val backupFile = File(context.filesDir, "$BACKUP_DIR/$backupFileName")
            ZipFile(backupFile).use { zip ->
                val meta = zip.getEntry("meta.json")
                    ?: return@withContext false
                val json = JSONObject(zip.getInputStream(meta).bufferedReader().readText())
                val expectedCount = json.optInt("itemCount", -1)
                val actualCount   = zip.entries().toList().size - 1  // exclude meta
                val valid = expectedCount < 0 || actualCount == expectedCount
                Log.i(TAG, "Backup verification: $valid (expected=$expectedCount actual=$actualCount)")
                valid
            }
        } catch (e: Exception) {
            Log.e(TAG, "verifyBackup failed: ${e.message}")
            false
        }
    }

    // ── Delete a backup file ──────────────────────────────────────────────────
    fun deleteBackup(backupFileName: String): Boolean {
        return try {
            File(context.filesDir, "$BACKUP_DIR/$backupFileName").delete().also {
                if (it) Log.i(TAG, "Deleted backup: $backupFileName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteBackup failed: ${e.message}")
            false
        }
    }

    private fun readBackupMeta(zipFile: File): JSONObject {
        return ZipFile(zipFile).use { zip ->
            val entry = zip.getEntry("meta.json") ?: return JSONObject()
            JSONObject(zip.getInputStream(entry).bufferedReader().readText())
        }
    }

    private fun restoreSettings(json: JSONObject) {
        val prefs = context.getSharedPreferences("aladdin_settings", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        json.keys().forEach { key ->
            when (val v = json.get(key)) {
                is Boolean -> editor.putBoolean(key, v)
                is Int     -> editor.putInt(key, v)
                is Long    -> editor.putLong(key, v)
                is Float   -> editor.putFloat(key, v)
                is String  -> editor.putString(key, v)
            }
        }
        editor.apply()
    }

    private fun restoreDatabase(dbName: String, data: ByteArray) {
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()
        dbFile.writeBytes(data)
        Log.i(TAG, "DB '$dbName' restored (${data.size / 1024}KB)")
    }

    private fun restoreMemoryFile(name: String, data: ByteArray) {
        val memDir = File(context.filesDir, "memories")
        memDir.mkdirs()
        File(memDir, name).writeBytes(data)
    }
}
