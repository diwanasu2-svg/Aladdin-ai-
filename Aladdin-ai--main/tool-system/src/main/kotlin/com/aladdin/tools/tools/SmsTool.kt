package com.aladdin.tools.tools
import javax.inject.Singleton
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext

import android.content.Context
import android.net.Uri
import android.telephony.SmsManager
import com.aladdin.tools.tools.BaseTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Phase 9 — SMS Tool
 * Send SMS, read inbox, search messages, extract OTPs, detect spam via Android SmsManager + ContentResolver.
 */
@Singleton
class SmsTool @Inject constructor(@ApplicationContext private val context: Context) : BaseTool {

    override val id = "sms"

    override val name = "sms"
    override val description = "Send, read, search SMS messages; OTP extraction and spam detection"

    private val OTP_PATTERN = Pattern.compile("\\b(\\d{4,8})\\b")
    private val SPAM_KEYWORDS = listOf("win", "prize", "lottery", "click here", "urgent", "free money",
        "congratulations", "selected", "claim now", "limited time")

    // ── Send SMS ───────────────────────────────────────────────────────────
    @Suppress("MissingPermission")
    fun sendSms(to: String, body: String): ToolResult {
        return try {
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(body)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(to, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(to, null, body, null, null)
            }
            ToolResult.success(id, JSONObject().apply {
                put("sent", true); put("to", to)
                put("length", body.length); put("parts", parts.size)
            }.toString())
        } catch (e: Exception) {
            ToolResult.error(id, "SMS send failed: ${e.message}")
        }
    }

    // ── Read SMS inbox ────────────────────────────────────────────────────
    @Suppress("MissingPermission")
    suspend fun readInbox(limit: Int = 20, fromNumber: String? = null): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse("content://sms/inbox")
                val selection = fromNumber?.let { "address = ?" }
                val args = fromNumber?.let { arrayOf(it) }
                val cursor = context.contentResolver.query(uri,
                    arrayOf("_id", "address", "body", "date", "read"),
                    selection, args, "date DESC")
                val messages = mutableListOf<JSONObject>()
                cursor?.use { c ->
                    while (c.moveToNext() && messages.size < limit) {
                        val body = c.getString(2) ?: ""
                        messages.add(JSONObject().apply {
                            put("id", c.getLong(0))
                            put("from", c.getString(1) ?: "")
                            put("body", body)
                            put("date", c.getLong(3))
                            put("read", c.getInt(4) == 1)
                            put("is_otp", OTP_PATTERN.matcher(body).find())
                            put("is_spam", isSpam(body))
                        })
                    }
                }
                ToolResult.success(id, JSONObject().apply {
                    put("messages", messages.map { it.toString() }); put("count", messages.size)
                }.toString())
            } catch (e: Exception) {
                ToolResult.error(id, "Read SMS error: ${e.message}")
            }
        }

    // ── Search messages ───────────────────────────────────────────────────
    suspend fun searchSms(query: String, limit: Int = 20): ToolResult = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse("content://sms/")
            val cursor = context.contentResolver.query(uri,
                arrayOf("_id", "address", "body", "date"),
                "body LIKE ?", arrayOf("%$query%"), "date DESC")
            val results = mutableListOf<JSONObject>()
            cursor?.use { c ->
                while (c.moveToNext() && results.size < limit) {
                    results.add(JSONObject().apply {
                        put("id", c.getLong(0)); put("from", c.getString(1) ?: "")
                        put("body", c.getString(2) ?: ""); put("date", c.getLong(3))
                    })
                }
            }
            ToolResult.success(id, JSONObject().apply {
                put("results", results.map { it.toString() }); put("count", results.size)
            }.toString())
        } catch (e: Exception) {
            ToolResult.error(id, "Search SMS error: ${e.message}")
        }
    }

    // ── Extract OTP ───────────────────────────────────────────────────────
    suspend fun extractOtp(fromNumber: String? = null): ToolResult = withContext(Dispatchers.IO) {
        val result = readInbox(10, fromNumber)
        if (!result.success) return@withContext result
        val data = try { JSONObject(result.output) } catch (e: Exception) {
            return@withContext ToolResult.error(id, "No data")
        }
        val messages = data.getJSONArray("messages")
        val otps = mutableListOf<JSONObject>()
        for (i in 0 until messages.length()) {
            val msg = JSONObject(messages.getString(i))
            val body = msg.optString("body")
            val matcher = OTP_PATTERN.matcher(body)
            if (matcher.find()) {
                otps.add(JSONObject().apply {
                    put("otp", matcher.group(1)); put("from", msg.optString("from"))
                    put("body", body); put("date", msg.optLong("date"))
                })
            }
        }
        ToolResult.success(id, JSONObject().apply {
            put("otps", otps.map { it.toString() }); put("count", otps.size)
        }.toString())
    }

    private fun isSpam(body: String): Boolean {
        val lower = body.lowercase()
        return SPAM_KEYWORDS.any { lower.contains(it) }
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return when (val action = (params["action"] ?: "read")) {
            "send" -> sendSms((params["to"] ?: return ToolResult.error(id, "Missing required parameter: " + "to")), (params["body"] ?: return ToolResult.error(id, "Missing required parameter: " + "body")))
            "read" -> readInbox((params["limit"]?.toIntOrNull() ?: 20), (params["from"] ?: "").ifEmpty { null })
            "search" -> searchSms((params["query"] ?: return ToolResult.error(id, "Missing required parameter: " + "query")), (params["limit"]?.toIntOrNull() ?: 20))
            "otp" -> extractOtp((params["from"] ?: "").ifEmpty { null })
            else -> ToolResult.error(id, "Unknown SMS action: $action")
        }
    }
}
