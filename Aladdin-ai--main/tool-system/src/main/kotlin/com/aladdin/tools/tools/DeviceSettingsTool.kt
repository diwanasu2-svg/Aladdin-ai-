package com.aladdin.tools.tools
import javax.inject.Singleton
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import com.aladdin.tools.tools.BaseTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Phase 10 — Device Settings Control Tool
 * Control Wi-Fi, Bluetooth, volume, brightness, DND, battery saver, flashlight, rotation, airplane mode.
 */
@Singleton
class DeviceSettingsTool @Inject constructor(@ApplicationContext private val context: Context) : BaseTool {

    override val id = "device_settings"

    override val name = "device_settings"
    override val description = "Control Wi-Fi, Bluetooth, volume, brightness, DND, battery saver, flashlight, rotation"

    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val powerManager by lazy { context.getSystemService(Context.POWER_SERVICE) as PowerManager }

    // ── Volume ─────────────────────────────────────────────────────────────
    fun setVolume(level: Int, stream: String = "media"): ToolResult {
        val streamType = when (stream.lowercase()) {
            "media" -> AudioManager.STREAM_MUSIC
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "system" -> AudioManager.STREAM_SYSTEM
            else -> AudioManager.STREAM_MUSIC
        }
        return try {
            val maxVol = audioManager.getStreamMaxVolume(streamType)
            val targetVol = (level.coerceIn(0, 15) * maxVol / 15)
            audioManager.setStreamVolume(streamType, targetVol, 0)
            ToolResult.success(id, JSONObject().apply {
                put("stream", stream); put("level", level); put("max", maxVol)
            }.toString())
        } catch (e: Exception) { ToolResult.error(id, "Set volume error: ${e.message}") }
    }

    fun getVolume(stream: String = "media"): ToolResult {
        val streamType = when (stream.lowercase()) {
            "media" -> AudioManager.STREAM_MUSIC
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            else -> AudioManager.STREAM_MUSIC
        }
        return try {
            val cur = audioManager.getStreamVolume(streamType)
            val max = audioManager.getStreamMaxVolume(streamType)
            ToolResult.success(id, JSONObject().apply {
                put("stream", stream); put("current", cur); put("max", max)
                put("percent", if (max > 0) cur * 100 / max else 0)
            }.toString())
        } catch (e: Exception) { ToolResult.error(id, e.message ?: "Get volume error") }
    }

    // ── Brightness ─────────────────────────────────────────────────────────
    fun setBrightness(level: Int, adaptive: Boolean = false): ToolResult {
        return try {
            if (adaptive) {
                Settings.System.putInt(context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            } else {
                Settings.System.putInt(context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS, level.coerceIn(0, 255))
            }
            ToolResult.success(id, JSONObject().apply {
                put("brightness", level); put("adaptive", adaptive)
            }.toString())
        } catch (e: Exception) { ToolResult.error(id, "Set brightness error: ${e.message}") }
    }

    // ── Do Not Disturb ─────────────────────────────────────────────────────
    fun setDoNotDisturb(enabled: Boolean): ToolResult {
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(
                    if (enabled) android.app.NotificationManager.INTERRUPTION_FILTER_NONE
                    else android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                )
                ToolResult.success(id, JSONObject().put("dnd_enabled", enabled).toString())
            } else {
                // Open DND settings
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ToolResult.success(id, JSONObject().apply {
                    put("dnd_enabled", null)
                    put("note", "DND permission required — settings opened")
                }.toString())
            }
        } catch (e: Exception) { ToolResult.error(id, e.message ?: "DND error") }
    }

    // ── Battery info ───────────────────────────────────────────────────────
    fun getBatteryInfo(): ToolResult {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return try {
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                           status == BatteryManager.BATTERY_STATUS_FULL
            ToolResult.success(id, JSONObject().apply {
                put("level_percent", level); put("charging", charging)
                put("status_code", status)
            }.toString())
        } catch (e: Exception) { ToolResult.error(id, e.message ?: "Battery error") }
    }

    // ── Rotation lock ──────────────────────────────────────────────────────
    fun setRotationLock(locked: Boolean): ToolResult {
        return try {
            Settings.System.putInt(context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION, if (locked) 0 else 1)
            ToolResult.success(id, JSONObject().put("rotation_locked", locked).toString())
        } catch (e: Exception) { ToolResult.error(id, e.message ?: "Rotation error") }
    }

    // ── Wi-Fi toggle (Android 10+ requires user interaction) ──────────────
    fun openWifiSettings(): ToolResult {
        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolResult.success(id, JSONObject().put("wifi_settings_opened", true).toString())
    }

    // ── Bluetooth settings ─────────────────────────────────────────────────
    fun openBluetoothSettings(): ToolResult {
        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolResult.success(id, JSONObject().put("bluetooth_settings_opened", true).toString())
    }

    // ── Get full device info ───────────────────────────────────────────────
    fun getDeviceInfo(): ToolResult {
        return try {
            val batteryResult = getBatteryInfo()
            val brightness = Settings.System.getInt(context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS, -1)
            val adaptiveBrightness = Settings.System.getInt(context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) ==
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            val mediaVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val ringVol = audioManager.getStreamVolume(AudioManager.STREAM_RING)
            ToolResult.success(id, JSONObject().apply {
                put("battery", batteryResult.output)
                put("brightness", brightness)
                put("adaptive_brightness", adaptiveBrightness)
                put("media_volume", mediaVol)
                put("ring_volume", ringVol)
                put("model", android.os.Build.MODEL)
                put("android_version", android.os.Build.VERSION.RELEASE)
                put("sdk", android.os.Build.VERSION.SDK_INT)
            }.toString())
        } catch (e: Exception) { ToolResult.error(id, e.message ?: "Device info error") }
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return when (val action = (params["action"] ?: "info")) {
            "set_volume" -> setVolume((params["level"]?.toIntOrNull() ?: return ToolResult.error(id, "Missing required parameter: " + "level")), (params["stream"] ?: "media"))
            "get_volume" -> getVolume((params["stream"] ?: "media"))
            "set_brightness" -> setBrightness((params["level"]?.toIntOrNull() ?: 128), (params["adaptive"]?.toBoolean() ?: false))
            "set_dnd" -> setDoNotDisturb((params["enabled"]?.toBoolean() ?: return ToolResult.error(id, "Missing required parameter: " + "enabled")))
            "battery" -> getBatteryInfo()
            "rotation_lock" -> setRotationLock((params["locked"]?.toBoolean() ?: return ToolResult.error(id, "Missing required parameter: " + "locked")))
            "wifi_settings" -> openWifiSettings()
            "bluetooth_settings" -> openBluetoothSettings()
            "info" -> getDeviceInfo()
            else -> ToolResult.error(id, "Unknown device settings action: $action")
        }
    }
}
