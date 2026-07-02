package com.aladdin.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.TextView
import com.aladdin.app.conversation.ConversationState
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LockScreenOverlay"
private const val DISMISS_TIMEOUT_MS = 5_000L   // Phase 6 Item 4: 5-second auto-dismiss

/**
 * LockScreenOverlay — Phase 6 Item 4: Full overlay implementation.
 *
 * Features added / fixed:
 *  • Appears over the lock screen via TYPE_APPLICATION_OVERLAY + FLAG_SHOW_WHEN_LOCKED
 *  • Touch drag to reposition using onTouch ACTION_MOVE
 *  • Swipe-down gesture to dismiss (deltaY > 200 dp)
 *  • Auto-dismiss after [DISMISS_TIMEOUT_MS] of inactivity
 *  • Mic and Dismiss button callbacks
 *  • updateState() syncs label to ConversationState
 *
 * Requires: android.permission.SYSTEM_ALERT_WINDOW
 */
@Singleton
class LockScreenOverlay @Inject constructor(
    private val context: Context
) {
    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: View? = null
    private var tvStatus: TextView? = null
    private var tvResponse: TextView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    var onMicClicked: (() -> Unit)? = null
    var onDismissClicked: (() -> Unit)? = null

    val isShowing: Boolean get() = overlayView != null

    // ── Auto-dismiss runnable ─────────────────────────────────────────────────
    private val autoDismissRunnable = Runnable {
        Log.d(TAG, "Auto-dismiss triggered after ${DISMISS_TIMEOUT_MS}ms")
        hide()
        onDismissClicked?.invoke()
    }

    // ── Show / Hide ───────────────────────────────────────────────────────────

    fun show() {
        if (isShowing) return
        val params = buildLayoutParams()
        val view = buildOverlayView(params)
        overlayView = view
        try {
            windowManager.addView(view, params)
            Log.i(TAG, "Overlay shown")
            scheduleAutoDismiss()
        } catch (e: WindowManager.BadTokenException) {
            Log.e(TAG, "BadTokenException — SYSTEM_ALERT_WINDOW not granted: ${e.message}")
            overlayView = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view: ${e.message}")
            overlayView = null
        }
    }

    fun hide() {
        mainHandler.removeCallbacks(autoDismissRunnable)
        overlayView?.let { view ->
            try { windowManager.removeView(view) } catch (_: Exception) {}
            overlayView = null
            tvStatus = null
            tvResponse = null
            Log.i(TAG, "Overlay hidden")
        }
    }

    fun updateState(state: ConversationState) {
        tvStatus?.text = when (state) {
            is ConversationState.Listening  -> "Listening…"
            is ConversationState.Processing -> "Thinking…"
            is ConversationState.Speaking   -> "Speaking…"
            else                            -> "Tap mic to speak"
        }
        // Reset auto-dismiss timer on any state update so active sessions don't time out
        if (state !is ConversationState.Idle) resetAutoDismiss()
    }

    fun showResponse(text: String) {
        tvResponse?.text = text
        resetAutoDismiss()
    }

    // ── Layout params ─────────────────────────────────────────────────────────

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 120
        }
    }

    // ── Programmatic view (no XML dependency) ─────────────────────────────────

    private fun buildOverlayView(params: WindowManager.LayoutParams): View {
        val density = context.resources.displayMetrics.density

        // Root container
        val root = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16, density), dp(16, density), dp(16, density), dp(24, density))
            setBackgroundColor(0xEE1A1A2E.toInt())
        }

        // Status text
        val tvSt = TextView(context).apply {
            text      = "Aladdin AI"
            textSize  = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity   = Gravity.CENTER
        }
        tvStatus = tvSt

        // Response text
        val tvResp = TextView(context).apply {
            text      = ""
            textSize  = 13f
            setTextColor(0xAAFFFFFF.toInt())
            gravity   = Gravity.CENTER
            maxLines  = 3
        }
        tvResponse = tvResp

        // Buttons row
        val btnRow = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12, density), 0, 0)
        }

        val micBtn = android.widget.Button(context).apply {
            text = "🎙 Mic"
            setOnClickListener {
                resetAutoDismiss()
                onMicClicked?.invoke()
            }
        }
        val dismissBtn = android.widget.Button(context).apply {
            text = "✕ Dismiss"
            setOnClickListener { hide(); onDismissClicked?.invoke() }
        }

        val lp = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(8, density), 0, dp(8, density), 0) }

        btnRow.addView(micBtn, lp)
        btnRow.addView(dismissBtn, lp)

        root.addView(tvSt)
        root.addView(tvResp)
        root.addView(btnRow)

        // ── Touch handler: drag to reposition + swipe-down to dismiss ─────────
        var rawX = 0f; var rawY = 0f
        var startParamsX = 0; var startParamsY = 0

        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    rawX = event.rawX; rawY = event.rawY
                    startParamsX = params.x; startParamsY = params.y
                    resetAutoDismiss()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - rawX).toInt()
                    val dy = (event.rawY - rawY).toInt()
                    params.x = startParamsX + dx
                    params.y = startParamsY - dy   // y is from bottom
                    try { windowManager.updateViewLayout(root, params) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dy = event.rawY - rawY
                    // Swipe down (deltaY > 200px) → dismiss
                    if (dy > 200f) {
                        Log.d(TAG, "Swipe-down dismiss detected")
                        hide(); onDismissClicked?.invoke()
                    }
                    true
                }
                else -> false
            }
        }

        return root
    }

    // ── Auto-dismiss helpers ──────────────────────────────────────────────────

    private fun scheduleAutoDismiss() {
        mainHandler.removeCallbacks(autoDismissRunnable)
        mainHandler.postDelayed(autoDismissRunnable, DISMISS_TIMEOUT_MS)
    }

    private fun resetAutoDismiss() {
        mainHandler.removeCallbacks(autoDismissRunnable)
        mainHandler.postDelayed(autoDismissRunnable, DISMISS_TIMEOUT_MS)
    }

    private fun dp(dp: Int, density: Float) = (dp * density).toInt()
}
