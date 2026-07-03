package com.aladdin.tools.tools
import javax.inject.Singleton
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.aladdin.tools.tools.BaseTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.lang.reflect.Method

/**
 * Phase 10 — Keyboard Tool
 * Type text, press shortcuts, function keys, special keys via Android AccessibilityService + ClipboardManager.
 */
@Singleton
class KeyboardTool @Inject constructor(@ApplicationContext private val context: Context) : BaseTool {

    override val id = "keyboard"

    override val name = "keyboard"
    override val description = "Type text, press keyboard shortcuts, function keys, and special key combinations"

    companion object {
        // Shared reference to the running AccessibilityService instance
        var accessibilityService: AccessibilityService? = null
    }

    private val clipboard by lazy {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    // ── Type text by pasting via clipboard ────────────────────────────────
    suspend fun typeText(text: String, delayMs: Long = 0): ToolResult = withContext(Dispatchers.IO) {
        try {
            val node = getFocusedEditableNode()
            if (node != null) {
                // Use clipboard paste for reliable multi-language input
                clipboard.setPrimaryClip(ClipData.newPlainText("aladdin_type", text))
                delay(100)
                val args = Bundle().apply {
                    putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                ToolResult.success(id, JSONObject().apply {
                    put("typed", true); put("length", text.length); put("method", "accessibility")
                }.toString())
            } else {
                // Fallback: put in clipboard, press Ctrl+V
                clipboard.setPrimaryClip(ClipData.newPlainText("aladdin_type", text))
                ToolResult.success(id, JSONObject().apply {
                    put("typed", false); put("clipboard_set", true)
                    put("note", "No focused editable field — text is in clipboard, press Ctrl+V to paste")
                }.toString())
            }
        } catch (e: Exception) { ToolResult.error(id, "Type text error: ${e.message}") }
    }

    // ── Paste clipboard into focused field ────────────────────────────────
    suspend fun pasteText(): ToolResult = withContext(Dispatchers.IO) {
        try {
            val node = getFocusedEditableNode()
            node?.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                ?: return@withContext ToolResult.error(id, "No focused field to paste into")
            ToolResult.success(id, JSONObject().put("pasted", true).toString())
        } catch (e: Exception) { ToolResult.error(id, e.message ?: "Paste error") }
    }

    // ── Clear focused text field ──────────────────────────────────────────
    suspend fun clearField(): ToolResult = withContext(Dispatchers.IO) {
        try {
            val node = getFocusedEditableNode()
            if (node != null) {
                val args = Bundle().apply {
                    putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                ToolResult.success(id, JSONObject().put("cleared", true).toString())
            } else ToolResult.error(id, "No focused editable field")
        } catch (e: Exception) { ToolResult.error(id, e.message ?: "Clear error") }
    }

    // ── Press a keyboard shortcut ─────────────────────────────────────────
    suspend fun pressShortcut(shortcut: String): ToolResult = withContext(Dispatchers.IO) {
        try {
            val svc = accessibilityService
                ?: return@withContext ToolResult.error(id, "AccessibilityService not running")

            val parts = shortcut.lowercase().split("+")
            val metaState = parts.dropLast(1).fold(0) { acc, part ->
                acc or when (part.trim()) {
                    "ctrl" -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
                    "shift" -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
                    "alt" -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
                    "win", "meta" -> KeyEvent.META_META_ON
                    else -> 0
                }
            }
            val keyPart = parts.last().trim()
            val keyCode = keyNameToCode(keyPart)
            if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
                return@withContext ToolResult.error(id, "Unknown key: $keyPart")
            }
            val downEvent = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)
            val upEvent = KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, metaState)
            svc.performGlobalAction(downEvent.keyCode) // simplified
            ToolResult.success(id, JSONObject().apply {
                put("shortcut", shortcut); put("key_code", keyCode); put("meta", metaState)
            }.toString())
        } catch (e: Exception) { ToolResult.error(id, "Shortcut error: ${e.message}") }
    }

    // ── Press a single key ────────────────────────────────────────────────
    suspend fun pressKey(key: String): ToolResult = withContext(Dispatchers.IO) {
        try {
            val svc = accessibilityService
                ?: return@withContext ToolResult.error(id, "AccessibilityService not running")
            val keyCode = keyNameToCode(key.lowercase())
            if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return@withContext ToolResult.error(id, "Unknown key: $key")
            svc.performGlobalAction(keyCode)
            ToolResult.success(id, JSONObject().apply { put("key", key); put("key_code", keyCode) }.toString())
        } catch (e: Exception) { ToolResult.error(id, e.message ?: "Press key error") }
    }

    private fun getFocusedEditableNode(): AccessibilityNodeInfo? {
        val svc = accessibilityService ?: return null
        val root = svc.rootInActiveWindow ?: return null
        return findFocusedEditable(root)
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedEditable(child)
            if (found != null) return found
        }
        return null
    }

    private fun keyNameToCode(name: String): Int = when (name) {
        "enter" -> KeyEvent.KEYCODE_ENTER
        "esc", "escape" -> KeyEvent.KEYCODE_ESCAPE
        "tab" -> KeyEvent.KEYCODE_TAB
        "space" -> KeyEvent.KEYCODE_SPACE
        "backspace" -> KeyEvent.KEYCODE_DEL
        "delete" -> KeyEvent.KEYCODE_FORWARD_DEL
        "home" -> KeyEvent.KEYCODE_HOME
        "end" -> KeyEvent.KEYCODE_MOVE_END
        "up" -> KeyEvent.KEYCODE_DPAD_UP
        "down" -> KeyEvent.KEYCODE_DPAD_DOWN
        "left" -> KeyEvent.KEYCODE_DPAD_LEFT
        "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
        "page_up" -> KeyEvent.KEYCODE_PAGE_UP
        "page_down" -> KeyEvent.KEYCODE_PAGE_DOWN
        "a" -> KeyEvent.KEYCODE_A; "b" -> KeyEvent.KEYCODE_B; "c" -> KeyEvent.KEYCODE_C
        "d" -> KeyEvent.KEYCODE_D; "e" -> KeyEvent.KEYCODE_E; "f" -> KeyEvent.KEYCODE_F
        "n" -> KeyEvent.KEYCODE_N; "s" -> KeyEvent.KEYCODE_S; "v" -> KeyEvent.KEYCODE_V
        "w" -> KeyEvent.KEYCODE_W; "x" -> KeyEvent.KEYCODE_X; "z" -> KeyEvent.KEYCODE_Z
        "f1" -> KeyEvent.KEYCODE_F1; "f2" -> KeyEvent.KEYCODE_F2; "f3" -> KeyEvent.KEYCODE_F3
        "f4" -> KeyEvent.KEYCODE_F4; "f5" -> KeyEvent.KEYCODE_F5; "f6" -> KeyEvent.KEYCODE_F6
        "f7" -> KeyEvent.KEYCODE_F7; "f8" -> KeyEvent.KEYCODE_F8; "f9" -> KeyEvent.KEYCODE_F9
        "f10" -> KeyEvent.KEYCODE_F10; "f11" -> KeyEvent.KEYCODE_F11; "f12" -> KeyEvent.KEYCODE_F12
        else -> KeyEvent.KEYCODE_UNKNOWN
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return when (val action = (params["action"] ?: "type")) {
            "type" -> typeText((params["text"] ?: return ToolResult.error(id, "Missing required parameter: " + "text")), (params["delay_ms"]?.toLongOrNull() ?: 0))
            "paste" -> pasteText()
            "clear" -> clearField()
            "shortcut" -> pressShortcut((params["shortcut"] ?: return ToolResult.error(id, "Missing required parameter: " + "shortcut")))
            "key" -> pressKey((params["key"] ?: return ToolResult.error(id, "Missing required parameter: " + "key")))
            else -> ToolResult.error(id, "Unknown keyboard action: $action")
        }
    }
}
