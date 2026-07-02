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
class DeviceSettingsTool @Inject constructor(@ApplicationContext private val context: Context) : BaseTool() {

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
            ToolResult.success(JSONObject().apply {
                put("stream", stream); put("level", level); put("max", maxVol)
            })
        } catch (e: Exception) { ToolResult.error("Set volume error: ${e.message}") }
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
            ToolResult.success(JSONObject().apply {
                put("stream", stream); put("current", cur); put("max", max)
                put("percent", if (max > 0) cur * 100 / max else 0)
            })
        } catch (e: Exception) { ToolResult.error(e.message ?: "Get volume error") }
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
            ToolResult.success(JSONObject().apply {
                put("brightness", level); put("adaptive", adaptive)
            })
        } catch (e: Exception) { ToolResult.error("Set brightness error: ${e.message}") }
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
                ToolResult.success(JSONObject().put("dnd_enabled", enabled))
            } else {
                // Open DND settings
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ToolResult.success(JSONObject().apply {
                    put("dnd_enabled", null)
                    put("note", "DND permission required — settings opened")
                })
            }
        } catch (e: Exception) { ToolResult.error(e.message ?: "DND error") }
    }

    // ── Battery info ───────────────────────────────────────────────────────
    fun getBatteryInfo(): ToolResult {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return try {
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                           status == BatteryManager.BATTERY_STATUS_FULL
            ToolResult.success(JSONObject().apply {
                put("level_percent", level); put("charging", charging)
                put("status_code", status)
            })
        } catch (e: Exception) { ToolResult.error(e.message ?: "Battery error") }
    }

    // ── Rotation lock ──────────────────────────────────────────────────────
    fun setRotationLock(locked: Boolean): ToolResult {
        return try {
            Settings.System.putInt(context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION, if (locked) 0 else 1)
            ToolResult.success(JSONObject().put("rotation_locked", locked))
        } catch (e: Exception) { ToolResult.error(e.message ?: "Rotation error") }
    }

    // ── Wi-Fi toggle (Android 10+ requires user interaction) ──────────────
    fun openWifiSettings(): ToolResult {
        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolResult.success(JSONObject().put("wifi_settings_opened", true))
    }

    // ── Bluetooth settings ─────────────────────────────────────────────────
    fun openBluetoothSettings(): ToolResult {
        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolResult.success(JSONObject().put("bluetooth_settings_opened", true))
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
            ToolResult.success(JSONObject().apply {
                put("battery", batteryResult.data?.toString() ?: "")
                put("brightness", brightness)
                put("adaptive_brightness", adaptiveBrightness)
                put("media_volume", mediaVol)
                put("ring_volume", ringVol)
                put("model", android.os.Build.MODEL)
                put("android_version", android.os.Build.VERSION.RELEASE)
                put("sdk", android.os.Build.VERSION.SDK_INT)
            })
        } catch (e: Exception) { ToolResult.error(e.message ?: "Device info error") }
    }

    override suspend fun execute(params: JSONObject): ToolResult {
        return when (val action = params.optString("action", "info")) {
            "set_volume" -> setVolume(params.getInt("level"), params.optString("stream", "media"))
            "get_volume" -> getVolume(params.optString("stream", "media"))
            "set_brightness" -> setBrightness(params.optInt("level", 128), params.optBoolean("adaptive", false))
            "set_dnd" -> setDoNotDisturb(params.getBoolean("enabled"))
            "battery" -> getBatteryInfo()
            "rotation_lock" -> setRotationLock(params.getBoolean("locked"))
            "wifi_settings" -> openWifiSettings()
            "bluetooth_settings" -> openBluetoothSettings()
            "info" -> getDeviceInfo()
            else -> ToolResult.error("Unknown device settings action: $action")
        }
    }
}
