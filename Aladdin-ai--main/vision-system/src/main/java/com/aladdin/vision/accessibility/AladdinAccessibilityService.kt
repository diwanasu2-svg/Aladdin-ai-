package com.aladdin.vision.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

private const val TAG = "AladdinAccessibility"

/**
 * AladdinAccessibilityService captures and exposes screen content via the Android Accessibility API.
 *
 * To enable:
 * 1. Declare in AndroidManifest.xml (see manifest in this module).
 * 2. Point the user to Settings → Accessibility → Aladdin Vision to enable it.
 * 3. Once enabled, [instance] will be non-null and [getScreenContent] will return live data.
 *
 * Provides:
 * - Full screen content extraction ([getScreenContent])
 * - Node lookup by text ([findNodesByText])
 * - Node lookup by resource ID ([findNodeById])
 * - UI element detection with bounds, type, content description
 */
class AladdinAccessibilityService : AccessibilityService() {

    private var lastEvent: AccessibilityEvent? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEvent = event
        Log.v(TAG, "Event: ${event?.eventType} from ${event?.packageName}")
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "Accessibility service destroyed")
    }

    // ─── Screen Content Extraction ────────────────────────────────────────────

    /**
     * Extract the full content of the current screen as a [ScreenContent] tree.
     */
    fun getScreenContent(): ScreenContent {
        val root = rootInActiveWindow ?: return ScreenContent(
            packageName = lastEvent?.packageName?.toString() ?: "unknown",
            windowTitle = "",
            elements = emptyList()
        )

        val elements = mutableListOf<UIElement>()
        extractNodes(root, elements, depth = 0)
        root.recycle()

        return ScreenContent(
            packageName = root.packageName?.toString() ?: lastEvent?.packageName?.toString() ?: "unknown",
            windowTitle = lastEvent?.text?.firstOrNull()?.toString() ?: "",
            elements = elements
        )
    }

    private fun extractNodes(node: AccessibilityNodeInfo, into: MutableList<UIElement>, depth: Int) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        into.add(
            UIElement(
                text = node.text?.toString() ?: "",
                contentDescription = node.contentDescription?.toString() ?: "",
                className = node.className?.toString() ?: "",
                resourceId = node.viewIdResourceName ?: "",
                bounds = bounds,
                depth = depth,
                isClickable = node.isClickable,
                isEditable = node.isEditable,
                isEnabled = node.isEnabled,
                isFocused = node.isFocused,
                isScrollable = node.isScrollable,
                isSelected = node.isSelected,
                isChecked = node.isChecked
            )
        )

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                extractNodes(child, into, depth + 1)
                child.recycle()
            }
        }
    }

    // ─── Node lookup helpers ──────────────────────────────────────────────────

    fun findNodesByText(text: String): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        return root.findAccessibilityNodeInfosByText(text)?.toList() ?: emptyList()
    }

    fun findNodeById(viewId: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()
    }

    // ─── Singleton ────────────────────────────────────────────────────────────

    companion object {
        @Volatile
        var instance: AladdinAccessibilityService? = null
            private set
    }
}
