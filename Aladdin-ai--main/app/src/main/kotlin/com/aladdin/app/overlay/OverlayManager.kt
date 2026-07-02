package com.aladdin.app.overlay

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.aladdin.app.conversation.ConversationManager
import com.aladdin.app.conversation.ConversationState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "OverlayManager"

/**
 * OverlayManager — coordinates [LockScreenOverlay] lifecycle with conversation state.
 *
 * - Observes [ConversationManager.currentState] and updates the overlay text in real time.
 * - Hides the overlay when state returns to Idle.
 * - Guards the SYSTEM_ALERT_WINDOW permission before showing.
 */
@Singleton
class OverlayManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val overlay: LockScreenOverlay,
    private val conversationManager: ConversationManager
) {
    private val scope = CoroutineScope(Dispatchers.Main)

    init {
        observeConversationState()
    }

    fun canShowOverlay(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context)
        else true

    fun showOnLockScreen(
        onMicClicked: () -> Unit,
        onDismissed: () -> Unit = {}
    ) {
        if (!canShowOverlay()) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted — cannot show overlay")
            return
        }
        overlay.onMicClicked = onMicClicked
        overlay.onDismissClicked = onDismissed
        overlay.show()
        overlay.updateState(conversationManager.currentState.value)
    }

    fun hide() = overlay.hide()

    private fun observeConversationState() {
        conversationManager.currentState
            .onEach { state ->
                if (overlay.isShowing) {
                    overlay.updateState(state)
                    if (state is ConversationState.Idle) {
                        overlay.hide()
                        Log.d(TAG, "Overlay hidden — conversation idle")
                    }
                }
            }
            .launchIn(scope)
    }
}
