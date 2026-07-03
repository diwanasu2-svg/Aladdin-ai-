package com.aladdin.tools.tools
import javax.inject.Singleton
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext

import android.content.Context
import android.content.Intent
import com.aladdin.tools.tools.BaseTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Phase 9 — Smart Home Tool
 * Control smart devices via Tuya Local API, Philips Hue, and Google Home / Alexa voice intents.
 */
@Singleton
class SmartHomeTool @Inject constructor(@ApplicationContext private val context: Context) : BaseTool {

    override val id = "smart_home"

    override val name = "smart_home"
    override val description = "Control smart lights, plugs, thermostats, locks, cameras and create routines"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    private val routines = mutableMapOf<String, JSONObject>()

    // ── Tuya Local API helper ─────────────────────────────────────────────
    private suspend fun tuyaRequest(ip: String, path: String, payload: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("http://$ip$path").post(body).build()
            val resp = httpClient.newCall(req).execute()
            JSONObject(resp.body?.string() ?: "{}")
        }

    // ── Philips Hue helper ─────────────────────────────────────────────────
    private fun hueBaseUrl(): String {
        val ip = System.getenv("HUE_BRIDGE_IP") ?: ""
        val user = System.getenv("HUE_USER") ?: ""
        return "http://$ip/api/$user"
    }

    private suspend fun hueRequest(path: String, method: String = "GET", body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val reqBuilder = Request.Builder().url("${hueBaseUrl()}$path")
            when (method) {
                "PUT" -> reqBuilder.put((body ?: JSONObject()).toString()
                    .toRequestBody("application/json".toMediaType()))
                "POST" -> reqBuilder.post((body ?: JSONObject()).toString()
                    .toRequestBody("application/json".toMediaType()))
                else -> reqBuilder.get()
            }
            val resp = httpClient.newCall(reqBuilder.build()).execute()
            try { JSONObject(resp.body?.string() ?: "{}") } catch (e: Exception) { JSONObject() }
        }

    // ── Control light (Hue) ────────────────────────────────────────────────
    suspend fun controlLight(lightId: String, on: Boolean? = null, brightness: Int? = null,
                              hue: Int? = null, saturation: Int? = null): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val state = JSONObject()
                on?.let { state.put("on", it) }
                brightness?.let { state.put("bri", it.coerceIn(0, 254)) }
                hue?.let { state.put("hue", it) }
                saturation?.let { state.put("sat", it) }
                val result = hueRequest("/lights/$lightId/state", "PUT", state)
                ToolResult.success(id, JSONObject().apply {
                    put("light_id", lightId); put("state", state.toString()); put("result", result.toString())
                }.toString())
            } catch (e: Exception) {
                ToolResult.error(id, "Light control error: ${e.message}")
            }
        }

    // ── List Hue lights ────────────────────────────────────────────────────
    suspend fun listLights(): ToolResult = withContext(Dispatchers.IO) {
        try {
            val result = hueRequest("/lights")
            ToolResult.success(id, JSONObject().apply {
                put("lights", result.toString()); put("count", result.length())
            }.toString())
        } catch (e: Exception) { ToolResult.error(id, "List lights error: ${e.message}") }
    }

    // ── Control smart plug (Tuya) ──────────────────────────────────────────
    suspend fun controlPlug(deviceIp: String, on: Boolean): ToolResult {
        return try {
            val payload = JSONObject().apply { put("on", on) }
            val result = tuyaRequest(deviceIp, "/control", payload)
            ToolResult.success(id, JSONObject().apply {
                put("device_ip", deviceIp); put("on", on); put("result", result.toString())
            }.toString())
        } catch (e: Exception) { ToolResult.error(id, "Plug control error: ${e.message}") }
    }

    // ── Create routine ─────────────────────────────────────────────────────
    fun createRoutine(name: String, trigger: String, actions: List<JSONObject>): ToolResult {
        routines[name] = JSONObject().apply {
            put("name", name); put("trigger", trigger)
            put("actions", actions.map { it.toString() })
            put("created_at", System.currentTimeMillis()); put("enabled", true)
        }
        return ToolResult.success(id, JSONObject().apply {
            put("routine", name); put("trigger", trigger); put("actions", actions.size)
        }.toString())
    }

    // ── Voice command passthrough (Google Home / Alexa) ───────────────────
    fun sendVoiceCommand(assistant: String, command: String): ToolResult {
        val pkg = when (assistant.lowercase()) {
            "google" -> "com.google.android.googlequicksearchbox"
            "alexa" -> "com.amazon.dee.app"
            else -> return ToolResult.error(id, "Unknown assistant: $assistant")
        }
        val intent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
            setPackage(pkg); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ToolResult.success(id, JSONObject().apply {
            put("assistant", assistant); put("command", command); put("launched", true)
        }.toString())
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return when (val action = (params["action"] ?: "list_lights")) {
            "list_lights" -> listLights()
            "control_light" -> controlLight(
                (params["light_id"] ?: return ToolResult.error(id, "Missing required parameter: " + "light_id")),
                on = if (params.containsKey("on")) (params["on"]?.toBoolean() ?: return ToolResult.error(id, "Missing required parameter: " + "on")) else null,
                brightness = if (params.containsKey("brightness")) (params["brightness"]?.toIntOrNull() ?: return ToolResult.error(id, "Missing required parameter: " + "brightness")) else null,
                hue = if (params.containsKey("hue")) (params["hue"]?.toIntOrNull() ?: return ToolResult.error(id, "Missing required parameter: " + "hue")) else null,
                saturation = if (params.containsKey("saturation")) (params["saturation"]?.toIntOrNull() ?: return ToolResult.error(id, "Missing required parameter: " + "saturation")) else null
            )
            "control_plug" -> controlPlug((params["device_ip"] ?: return ToolResult.error(id, "Missing required parameter: " + "device_ip")), (params["on"]?.toBoolean() ?: return ToolResult.error(id, "Missing required parameter: " + "on")))
            "create_routine" -> {
                val actionsArr = org.json.JSONArray(params["actions"] ?: "[]")
                val actions = (0 until actionsArr.length()).map { JSONObject(actionsArr.getString(it)) }
                createRoutine((params["name"] ?: return ToolResult.error(id, "Missing required parameter: " + "name")), (params["trigger"] ?: return ToolResult.error(id, "Missing required parameter: " + "trigger")), actions)
            }
            "voice_command" -> sendVoiceCommand(
                (params["assistant"] ?: "google"), (params["command"] ?: return ToolResult.error(id, "Missing required parameter: " + "command"))
            )
            else -> ToolResult.error(id, "Unknown smart_home action: $action")
        }
    }
}


// ─────────────────────────────────────────────────────────────────
// Items 63-67: Smart home enhanced capabilities
// ─────────────────────────────────────────────────────────────────

// Item 63: HomeAssistant REST API integration
// Item 64: Accessibility service fallback control
// Item 65: Wake-word triggered automations
// Item 66: Natural language device control parser
// Item 67: Room-based device grouping for bulk control

/** Item 66: Parse natural-language voice command → smart home intent. */
data class SmartHomeIntent(
    val deviceType: String,   // light, switch, lock, thermostat, fan
    val action: String,       // turn_on, turn_off, toggle, set, lock, unlock
    val location: String,     // living_room, bedroom, kitchen, …
    val value: Int? = null    // brightness 0-100, temperature °F
)

fun parseSmartHomeCommand(command: String): SmartHomeIntent? {
    val lower = command.lowercase()
    val action = when {
        "turn on" in lower || "switch on" in lower || "enable" in lower -> "turn_on"
        "turn off" in lower || "switch off" in lower || "disable" in lower -> "turn_off"
        "toggle" in lower -> "toggle"
        "dim" in lower || "set" in lower || "percent" in lower -> "set"
        "lock" in lower -> "lock"
        "unlock" in lower -> "unlock"
        else -> return null
    }
    val deviceType = when {
        "light" in lower || "lamp" in lower || "bulb" in lower -> "light"
        "thermostat" in lower || "temperature" in lower || "heat" in lower -> "thermostat"
        "lock" in lower || "door" in lower -> "lock"
        "fan" in lower -> "fan"
        "plug" in lower || "outlet" in lower -> "switch"
        else -> "switch"
    }
    val rooms = listOf("living room", "bedroom", "kitchen", "bathroom", "office",
        "hallway", "garage", "basement", "dining room", "front", "back", "porch")
    val location = rooms.firstOrNull { lower.contains(it) }?.replace(" ", "_") ?: "all"
    val value = Regex("(\\d+)\\s*(?:percent|%|degrees|°)").find(lower)?.groupValues?.get(1)?.toIntOrNull()
    return SmartHomeIntent(deviceType, action, location, value)
}

/** Item 65: Voice-activated automation registry. */
object SmartHomeAutomationRegistry {
    data class Automation(val trigger: String, val action: suspend () -> Unit)
    private val automations = mutableListOf<Automation>()
    fun register(triggerPhrase: String, action: suspend () -> Unit) {
        automations.add(Automation(triggerPhrase.lowercase(), action))
        android.util.Log.i("SmartHomeAutomations", "Registered: '$triggerPhrase'")
    }
    suspend fun tryRun(voiceInput: String): Boolean {
        val lower = voiceInput.lowercase()
        return automations.firstOrNull { lower.contains(it.trigger) }
            ?.also { it.action() } != null
    }
    fun list() = automations.map { it.trigger }
}

/** Item 67: Bulk room control helper. */
data class RoomGroup(val name: String, val entityIds: List<String>)
object SmartHomeRooms {
    val rooms = mutableMapOf<String, RoomGroup>()
    fun defineRoom(name: String, vararg entityIds: String) {
        rooms[name.lowercase()] = RoomGroup(name, entityIds.toList())
    }
    fun getRoom(name: String) = rooms[name.lowercase()]
}

/** Item 63: HomeAssistant entity toggle via REST API. */
suspend fun toggleHomeAssistantEntity(entityId: String, baseUrl: String, token: String): Boolean {
    if (baseUrl.isBlank() || token.isBlank()) {
        android.util.Log.w("SmartHome", "HA not configured (baseUrl or token missing)")
        return false
    }
    return try {
        val url = "$baseUrl/api/services/homeassistant/toggle"
        val body = "{\"entity_id\":\"$entityId\"}"
        val client = okhttp3.OkHttpClient()
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val resp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { client.newCall(req).execute() }
        (resp.code in 200..299).also { android.util.Log.i("SmartHome", "Toggle $entityId: ${resp.code}") }
    } catch (e: Exception) { android.util.Log.e("SmartHome", "HA toggle failed: ${e.message}"); false }
}
