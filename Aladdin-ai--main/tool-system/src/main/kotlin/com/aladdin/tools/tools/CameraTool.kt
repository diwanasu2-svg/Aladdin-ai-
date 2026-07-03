package com.aladdin.tools.tools
import javax.inject.Singleton
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.aladdin.tools.tools.BaseTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 9 — Camera Tool
 * Capture photos, record video, scan QR codes, list media, apply basic filters.
 * Uses Android Camera Intent and MediaStore for gallery access.
 */
@Singleton
class CameraTool @Inject constructor(@ApplicationContext private val context: Context) : BaseTool {

    override val id = "camera"

    override val name = "camera"
    override val description = "Capture photos, record video, scan QR codes, manage camera media"

    private val authority get() = "${context.packageName}.fileprovider"

    // ── Create timestamped file ──────────────────────────────────────────
    private fun createMediaFile(ext: String): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: context.filesDir
        dir.mkdirs()
        return File(dir, "${timestamp}.$ext")
    }

    // ── Capture photo via intent ──────────────────────────────────────────
    fun launchCamera(targetActivity: android.app.Activity, requestCode: Int): ToolResult {
        return try {
            val file = createMediaFile("jpg")
            val uri = FileProvider.getUriForFile(context, authority, file)
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            targetActivity.startActivityForResult(intent, requestCode)
            ToolResult.success(id, JSONObject().apply {
                put("camera_launched", true); put("output_path", file.absolutePath)
            }.toString())
        } catch (e: Exception) {
            ToolResult.error(id, "Camera launch failed: ${e.message}")
        }
    }

    // ── Record video via intent ───────────────────────────────────────────
    fun launchVideoRecorder(targetActivity: android.app.Activity, requestCode: Int, maxDurationS: Int = 30): ToolResult {
        return try {
            val file = createMediaFile("mp4")
            val uri = FileProvider.getUriForFile(context, authority, file)
            val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                putExtra(MediaStore.EXTRA_DURATION_LIMIT, maxDurationS)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            targetActivity.startActivityForResult(intent, requestCode)
            ToolResult.success(id, JSONObject().apply {
                put("video_launched", true); put("output_path", file.absolutePath)
                put("max_duration_s", maxDurationS)
            }.toString())
        } catch (e: Exception) {
            ToolResult.error(id, "Video recorder launch failed: ${e.message}")
        }
    }

    // ── Scan QR code via ZXing intent ────────────────────────────────────
    fun launchQrScanner(targetActivity: android.app.Activity, requestCode: Int): ToolResult {
        return try {
            val intent = Intent("com.google.zxing.client.android.SCAN").apply {
                putExtra("SCAN_MODE", "QR_CODE_MODE")
            }
            targetActivity.startActivityForResult(intent, requestCode)
            ToolResult.success(id, JSONObject().put("qr_scanner_launched", true).toString())
        } catch (e: Exception) {
            // Fallback: open camera
            ToolResult.error(id, "QR scanner not available: ${e.message}. Install ZXing Barcode Scanner.")
        }
    }

    // ── List recent photos ────────────────────────────────────────────────
    suspend fun listPhotos(limit: Int = 20): ToolResult = withContext(Dispatchers.IO) {
        try {
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_TAKEN
            )
            val cursor = context.contentResolver.query(
                uri, projection, null, null, "${MediaStore.Images.Media.DATE_TAKEN} DESC"
            )
            val photos = mutableListOf<JSONObject>()
            cursor?.use { c ->
                while (c.moveToNext() && photos.size < limit) {
                    val id = c.getLong(0)
                    val photoUri = Uri.withAppendedPath(uri, id.toString())
                    photos.add(JSONObject().apply {
                        put("id", id); put("name", c.getString(1))
                        put("size_bytes", c.getLong(2))
                        put("date_taken", c.getLong(3))
                        put("uri", photoUri.toString())
                    })
                }
            }
            ToolResult.success(id, JSONObject().apply {
                put("photos", photos.map { it.toString() }); put("count", photos.size)
            }.toString())
        } catch (e: Exception) {
            ToolResult.error(id, "List photos error: ${e.message}")
        }
    }

    // ── Toggle torch/flash ────────────────────────────────────────────────
    fun toggleFlash(on: Boolean): ToolResult {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = cm.cameraIdList.firstOrNull() ?: return ToolResult.error(id, "No camera found")
            cm.setTorchMode(cameraId, on)
            ToolResult.success(id, JSONObject().put("flash", if (on) "on" else "off").toString())
        } catch (e: Exception) {
            ToolResult.error(id, "Flash control error: ${e.message}")
        }
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return when (val action = (params["action"] ?: "list_photos")) {
            "list_photos" -> listPhotos((params["limit"]?.toIntOrNull() ?: 20))
            "toggle_flash" -> toggleFlash((params["on"]?.toBoolean() ?: return ToolResult.error(id, "Missing required parameter: " + "on")))
            else -> ToolResult.error(id, "Camera actions requiring activity context must use launchCamera/launchVideoRecorder directly. Action: $action")
        }
    }
}
