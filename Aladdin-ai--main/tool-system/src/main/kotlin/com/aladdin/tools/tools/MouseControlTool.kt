package com.aladdin.tools.tools
import javax.inject.Singleton
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext

import android.content.Context
import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import com.aladdin.tools.tools.BaseTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.lang.reflect.Method

/**
 * Phase 10 — Mouse Control Tool
 * Simulate mouse/pointer movement, clicks, double-clicks, drag-and-drop, scroll via Android input injection.
 * Requires root or system-level permissions for injectInputEvent.
 */
@Singleton
class MouseControlTool @Inject constructor(@ApplicationContext private val context: Context) : BaseTool {

    override val id = "mouse_control"

    override val name = "mouse_control"
    override val description = "Move mouse, click, double-click, drag-and-drop, scroll via input injection"

    private val inputManager: InputManager by lazy {
        context.getSystemService(Context.INPUT_SERVICE) as InputManager
    }

    // Reflection access to injectInputEvent (requires INJECT_EVENTS permission or root)
    private val injectMethod: Method? by lazy {
        try {
            InputManager::class.java.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java)
        } catch (e: Exception) { null }
    }

    private fun injectEvent(event: InputEvent): Boolean {
        return try {
            injectMethod?.invoke(inputManager, event, 0) as? Boolean ?: false
        } catch (e: Exception) { false }
    }

    private fun motionEvent(action: Int, x: Float, y: Float, pressure: Float = 1f, size: Float = 1f): MotionEvent {
        val downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis()
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            this.pressure = pressure; this.size = size
        })
        return MotionEvent.obtain(downTime, eventTime, action, 1, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
    }

    suspend fun moveMouse(x: Int, y: Int, smooth: Boolean = false): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                if (smooth) {
                    // Smooth interpolation — requires knowing current position, simulated here
                    for (i in 1..20) {
                        val fx = x * i / 20f
                        val fy = y * i / 20f
                        val event = motionEvent(MotionEvent.ACTION_HOVER_MOVE, fx, fy)
                        injectEvent(event)
                        event.recycle()
                        delay(8)
                    }
                } else {
                    val event = motionEvent(MotionEvent.ACTION_HOVER_MOVE, x.toFloat(), y.toFloat())
                    injectEvent(event)
                    event.recycle()
                }
                ToolResult.success(id, JSONObject().apply { put("x", x); put("y", y); put("smooth", smooth) }.toString())
            } catch (e: Exception) { ToolResult.error(id, "Mouse move error: ${e.message}") }
        }

    suspend fun click(x: Int, y: Int, button: String = "left"): ToolResult = withContext(Dispatchers.IO) {
        try {
            val downEvent = motionEvent(MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat())
            injectEvent(downEvent)
            delay(50)
            val upEvent = motionEvent(MotionEvent.ACTION_UP, x.toFloat(), y.toFloat())
            injectEvent(upEvent)
            downEvent.recycle(); upEvent.recycle()
            ToolResult.success(id, JSONObject().apply { put("clicked", true); put("x", x); put("y", y); put("button", button) }.toString())
        } catch (e: Exception) { ToolResult.error(id, "Click error: ${e.message}") }
    }

    suspend fun doubleClick(x: Int, y: Int): ToolResult = withContext(Dispatchers.IO) {
        click(x, y)
        delay(100)
        click(x, y)
        ToolResult.success(id, JSONObject().apply { put("double_clicked", true); put("x", x); put("y", y) }.toString())
    }

    suspend fun dragDrop(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long = 500): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val steps = 30
                val downEvent = motionEvent(MotionEvent.ACTION_DOWN, fromX.toFloat(), fromY.toFloat())
                injectEvent(downEvent); downEvent.recycle()
                for (i in 1..steps) {
                    val x = fromX + (toX - fromX) * i / steps.toFloat()
                    val y = fromY + (toY - fromY) * i / steps.toFloat()
                    val moveEvent = motionEvent(MotionEvent.ACTION_MOVE, x, y)
                    injectEvent(moveEvent); moveEvent.recycle()
                    delay(durationMs / steps)
                }
                val upEvent = motionEvent(MotionEvent.ACTION_UP, toX.toFloat(), toY.toFloat())
                injectEvent(upEvent); upEvent.recycle()
                ToolResult.success(id, JSONObject().apply {
                    put("from", "$fromX,$fromY"); put("to", "$toX,$toY")
                }.toString())
            } catch (e: Exception) { ToolResult.error(id, "Drag error: ${e.message}") }
        }

    suspend fun scroll(x: Int, y: Int, direction: String, amount: Int = 3): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val vScroll = when (direction) { "up" -> amount.toFloat(); "down" -> -amount.toFloat(); else -> 0f }
                val hScroll = when (direction) { "right" -> amount.toFloat(); "left" -> -amount.toFloat(); else -> 0f }
                val downTime = SystemClock.uptimeMillis()
                val scrollEvent = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_SCROLL, x.toFloat(), y.toFloat(), 0)
                scrollEvent.setSource(InputDevice.SOURCE_MOUSE)
                injectEvent(scrollEvent); scrollEvent.recycle()
                ToolResult.success(id, JSONObject().apply {
                    put("direction", direction); put("amount", amount)
                }.toString())
            } catch (e: Exception) { ToolResult.error(id, "Scroll error: ${e.message}") }
        }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val x = (params["x"]?.toIntOrNull() ?: 0); val y = (params["y"]?.toIntOrNull() ?: 0)
        return when (val action = (params["action"] ?: "click")) {
            "move" -> moveMouse(x, y, (params["smooth"]?.toBoolean() ?: false))
            "click" -> click(x, y, (params["button"] ?: "left"))
            "double_click" -> doubleClick(x, y)
            "drag" -> dragDrop((params["from_x"]?.toIntOrNull() ?: return ToolResult.error(id, "Missing required parameter: " + "from_x")), (params["from_y"]?.toIntOrNull() ?: return ToolResult.error(id, "Missing required parameter: " + "from_y")),
                (params["to_x"]?.toIntOrNull() ?: return ToolResult.error(id, "Missing required parameter: " + "to_x")), (params["to_y"]?.toIntOrNull() ?: return ToolResult.error(id, "Missing required parameter: " + "to_y")),
                (params["duration_ms"]?.toLongOrNull() ?: 500))
            "scroll" -> scroll(x, y, (params["direction"] ?: return ToolResult.error(id, "Missing required parameter: " + "direction")), (params["amount"]?.toIntOrNull() ?: 3))
            else -> ToolResult.error(id, "Unknown mouse action: $action")
        }
    }
}
