package com.aladdin.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ObjectMemory — Item 58: Persistent object recognition and memory across sessions.
 * Remembers objects previously seen and enables "have you seen my keys?" queries.
 */
@Singleton
class ObjectMemory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG      = "ObjectMemory"
        private const val DB_FILE  = "object_memory.json"
        private const val MAX_ENTRIES = 500
    }

    data class ObjectRecord(
        val id: String,
        val label: String,
        val description: String,
        val timestamp: Long,
        val location: String = "",
        val confidence: Float = 1.0f,
        val imageHash: Int = 0
    )

    private val memoryFile = File(context.filesDir, DB_FILE)
    private val records = mutableListOf<ObjectRecord>()

    init { loadFromDisk() }

    // ── Store ─────────────────────────────────────────────────────────────────

    suspend fun remember(
        label: String, description: String = "", location: String = "",
        confidence: Float = 1.0f, bitmap: Bitmap? = null
    ) = withContext(Dispatchers.IO) {
        val hash = bitmap?.let {
            val px = IntArray(it.width * it.height)
            it.getPixels(px, 0, it.width, 0, 0, it.width, it.height)
            px.contentHashCode()
        } ?: 0

        val existing = records.indexOfFirst { it.label.equals(label, ignoreCase = true) && it.imageHash == hash }
        val record = ObjectRecord(
            id = "${label}_${System.currentTimeMillis()}",
            label = label, description = description, timestamp = System.currentTimeMillis(),
            location = location, confidence = confidence, imageHash = hash
        )
        if (existing >= 0) records[existing] = record else records.add(0, record)
        if (records.size > MAX_ENTRIES) records.subList(MAX_ENTRIES, records.size).clear()
        saveToDisk()
        Log.d(TAG, "Remembered: $label at $location")
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    fun recall(query: String): List<ObjectRecord> {
        val q = query.lowercase()
        return records.filter { r ->
            r.label.contains(q, ignoreCase = true) || r.description.contains(q, ignoreCase = true)
        }.sortedByDescending { it.timestamp }
    }

    fun recallRecent(limit: Int = 20): List<ObjectRecord> = records.take(limit)

    fun getLastSeen(label: String): ObjectRecord? =
        records.firstOrNull { it.label.equals(label, ignoreCase = true) }

    fun buildContextString(query: String): String {
        val matches = recall(query)
        if (matches.isEmpty()) return "I haven't seen $query recently."
        val latest = matches.first()
        val ago    = formatAge(System.currentTimeMillis() - latest.timestamp)
        val where  = if (latest.location.isNotBlank()) " near ${latest.location}" else ""
        return "I last saw '${latest.label}'$where about $ago ago."
    }

    fun forget(label: String) {
        records.removeAll { it.label.equals(label, ignoreCase = true) }
        saveToDisk()
    }

    fun clear() { records.clear(); saveToDisk() }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun saveToDisk() {
        try {
            val arr = JSONArray()
            records.forEach { r ->
                arr.put(JSONObject().apply {
                    put("id", r.id); put("label", r.label); put("description", r.description)
                    put("timestamp", r.timestamp); put("location", r.location)
                    put("confidence", r.confidence.toDouble()); put("imageHash", r.imageHash)
                })
            }
            memoryFile.writeText(arr.toString())
        } catch (e: Exception) { Log.e(TAG, "Save error: ${e.message}") }
    }

    private fun loadFromDisk() {
        if (!memoryFile.exists()) return
        try {
            val arr = JSONArray(memoryFile.readText())
            records.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                records.add(ObjectRecord(
                    id          = o.optString("id"),
                    label       = o.optString("label"),
                    description = o.optString("description"),
                    timestamp   = o.optLong("timestamp"),
                    location    = o.optString("location"),
                    confidence  = o.optDouble("confidence", 1.0).toFloat(),
                    imageHash   = o.optInt("imageHash")
                ))
            }
            Log.i(TAG, "Loaded ${records.size} object memories")
        } catch (e: Exception) { Log.e(TAG, "Load error: ${e.message}") }
    }

    private fun formatAge(ms: Long): String = when {
        ms < 60_000      -> "less than a minute"
        ms < 3_600_000   -> "${ms / 60_000} minute(s)"
        ms < 86_400_000  -> "${ms / 3_600_000} hour(s)"
        else             -> "${ms / 86_400_000} day(s)"
    }
}
