package com.aladdin.app.accessibility

import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AccessibilityHelper — Item 112: TalkBack support, content descriptions,
 * accessibility announcements.
 */
@Singleton
class AccessibilityHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

    val isTalkBackEnabled: Boolean
        get() = am.isEnabled && am.isTouchExplorationEnabled

    val isAccessibilityEnabled: Boolean
        get() = am.isEnabled

    fun announce(view: View, message: String) {
        view.announceForAccessibility(message)
    }

    fun setContentDescription(view: View, description: String) {
        view.contentDescription = description
    }

    fun markAsButton(view: View, label: String) {
        ViewCompat.setAccessibilityDelegate(view, object : androidx.core.view.AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.roleDescription = "Button"
                info.contentDescription = label
            }
        })
    }

    fun markAsHeading(view: View) {
        ViewCompat.setAccessibilityHeading(view, true)
    }

    fun setLiveRegion(view: View, mode: Int = View.ACCESSIBILITY_LIVE_REGION_POLITE) {
        ViewCompat.setAccessibilityLiveRegion(view, mode)
    }

    fun describeMicrophoneState(view: View, isListening: Boolean) {
        val desc = if (isListening) "Microphone active — Aladdin is listening" else "Microphone off — tap to speak"
        setContentDescription(view, desc)
    }

    fun describeAssistantState(view: View, state: String) {
        setContentDescription(view, "Aladdin: $state")
    }
}
