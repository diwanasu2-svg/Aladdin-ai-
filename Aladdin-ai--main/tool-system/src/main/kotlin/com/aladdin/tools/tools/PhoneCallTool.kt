package com.aladdin.tools.tools
import javax.inject.Singleton
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.TelecomManager
import com.aladdin.tools.tools.BaseTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Phase 9 — Phone Call Tool
 * Place calls, get call logs, schedule calls via Android TelecomManager / Call Intent.
 */
@Singleton
class PhoneCallTool @Inject constructor(@ApplicationContext private val context: Context) : BaseTool {

    override val id = "phone_call"

    override val name = "phone_call"
    override val description = "Place phone calls, read call logs, schedule and manage calls"

    private val scheduledCalls = mutableListOf<JSONObject>()

    // ── Place a call ───────────────────────────────────────────────────────
    @Suppress("MissingPermission")
    fun makeCall(number: String): ToolResult {
        return try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${Uri.encode(number)}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.success(id, JSONObject().apply {
                put("calling", number); put("status", "initiated")
            }.toString())
        } catch (e: Exception) {
            ToolResult.error(id, "Call failed: ${e.message}")
        }
    }

    // ── Open dial pad ──────────────────────────────────────────────────────
    fun dialNumber(number: String): ToolResult {
        return try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:${Uri.encode(number)}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.success(id, JSONObject().put("dial_opened", number).toString())
        } catch (e: Exception) {
            ToolResult.error(id, e.message ?: "Dial failed")
        }
    }

    // ── Read call log ──────────────────────────────────────────────────────
    @Suppress("MissingPermission")
    suspend fun getCallLog(limit: Int = 20, typeFilter: String = "all"): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val cursor = context.contentResolver.query(
                    android.provider.CallLog.Calls.CONTENT_URI,
                    arrayOf(
                        android.provider.CallLog.Calls.NUMBER,
                        android.provider.CallLog.Calls.TYPE,
                        android.provider.CallLog.Calls.DATE,
                        android.provider.CallLog.Calls.DURATION,
                        android.provider.CallLog.Calls.CACHED_NAME
                    ),
                    null, null,
                    "${android.provider.CallLog.Calls.DATE} DESC"
                )
                val calls = mutableListOf<JSONObject>()
                cursor?.use { c ->
                    while (c.moveToNext() && calls.size < limit) {
                        val type = when (c.getInt(1)) {
                            android.provider.CallLog.Calls.INCOMING_TYPE -> "incoming"
                            android.provider.CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                            android.provider.CallLog.Calls.MISSED_TYPE -> "missed"
                            else -> "unknown"
                        }
                        if (typeFilter != "all" && type != typeFilter) continue
                        calls.add(JSONObject().apply {
                            put("number", c.getString(0) ?: "")
                            put("type", type)
                            put("date", c.getLong(2))
                            put("duration_s", c.getLong(3))
                            put("name", c.getString(4) ?: "")
                        })
                    }
                }
                ToolResult.success(id, JSONObject().apply {
                    put("calls", calls.map { it.toString() }); put("count", calls.size)
                }.toString())
            } catch (e: Exception) {
                ToolResult.error(id, "Call log error: ${e.message}")
            }
        }

    // ── Schedule a call ────────────────────────────────────────────────────
    fun scheduleCall(number: String, atTimestamp: Long, note: String = ""): ToolResult {
        val entry = JSONObject().apply {
            put("number", number); put("scheduled_at", atTimestamp)
            put("note", note); put("status", "scheduled")
        }
        scheduledCalls.add(entry)
        return ToolResult.success(id, entry.toString())
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return when (val action = (params["action"] ?: "call")) {
            "call" -> makeCall((params["number"] ?: return ToolResult.error(id, "Missing required parameter: " + "number")))
            "dial" -> dialNumber((params["number"] ?: return ToolResult.error(id, "Missing required parameter: " + "number")))
            "log" -> getCallLog((params["limit"]?.toIntOrNull() ?: 20), (params["type"] ?: "all"))
            "schedule" -> scheduleCall(
                (params["number"] ?: return ToolResult.error(id, "Missing required parameter: " + "number")),
                (params["at_timestamp"]?.toLongOrNull() ?: return ToolResult.error(id, "Missing required parameter: " + "at_timestamp")),
                (params["note"] ?: "")
            )
            else -> ToolResult.error(id, "Unknown phone action: $action")
        }
    }
}
