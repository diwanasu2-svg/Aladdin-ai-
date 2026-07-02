package com.aladdin.app.conversation

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ConversationManager"
private const val MAX_TURNS = 20           // keep last 20 turns in context
private const val IDLE_TIMEOUT_MS = 30_000L // close conversation after 30 s silence

/**
 * ConversationManager — maintains the full dialog state for multi-turn voice conversations.
 *
 * Features:
 *  - Context window: last [MAX_TURNS] turns sent to the LLM on each request
 *  - Barge-in tracking: [isSpeaking] flag lets the TTS layer know if Aladdin is mid-sentence
 *  - Idle timeout: auto-closes the session after [IDLE_TIMEOUT_MS] ms of no activity
 *  - [currentState] StateFlow drives the UI (listening / thinking / speaking / idle)
 *
 * Wire this into your LLM / Gemini call site; the manager itself is model-agnostic.
 */
@Singleton
class ConversationManager @Inject constructor() {

    // ─── State ────────────────────────────────────────────────────────────────

    private val _currentState = MutableStateFlow<ConversationState>(ConversationState.Idle)
    val currentState: StateFlow<ConversationState> = _currentState.asStateFlow()

    private val _turns = MutableStateFlow<List<ConversationTurn>>(emptyList())
    val turns: StateFlow<List<ConversationTurn>> = _turns.asStateFlow()

    @Volatile var isSpeaking = false
        private set

    private var sessionId: String = newSessionId()
    private var lastActivityMs: Long = System.currentTimeMillis()

    // ─── Session control ──────────────────────────────────────────────────────

    fun startSession() {
        sessionId = newSessionId()
        _turns.value = emptyList()
        lastActivityMs = System.currentTimeMillis()
        transitionTo(ConversationState.Listening)
        Log.d(TAG, "Session started: $sessionId")
    }

    fun endSession() {
        _turns.value = emptyList()
        isSpeaking = false
        transitionTo(ConversationState.Idle)
        Log.d(TAG, "Session ended: $sessionId")
    }

    fun checkIdleTimeout() {
        if (System.currentTimeMillis() - lastActivityMs > IDLE_TIMEOUT_MS) {
            Log.d(TAG, "Session idle timeout — closing")
            endSession()
        }
    }

    // ─── Turn management ──────────────────────────────────────────────────────

    /** Call when ASR produces a transcription. Returns the turn added. */
    fun addUserTurn(text: String): ConversationTurn {
        lastActivityMs = System.currentTimeMillis()
        val turn = ConversationTurn(role = Role.USER, text = text)
        appendTurn(turn)
        transitionTo(ConversationState.Thinking)
        Log.d(TAG, "User: $text")
        return turn
    }

    /** Call when the LLM produces a response. Returns the turn added. */
    fun addAssistantTurn(text: String): ConversationTurn {
        lastActivityMs = System.currentTimeMillis()
        val turn = ConversationTurn(role = Role.ASSISTANT, text = text)
        appendTurn(turn)
        transitionTo(ConversationState.Speaking(text))
        isSpeaking = true
        Log.d(TAG, "Aladdin: $text")
        return turn
    }

    /** Call when TTS finishes speaking — returns to Listening state. */
    fun onSpeechFinished() {
        isSpeaking = false
        transitionTo(ConversationState.Listening)
        Log.d(TAG, "TTS finished — back to listening")
    }

    /**
     * Barge-in: user interrupts Aladdin mid-speech.
     * Stops TTS (caller must silence TTS engine) and resets to listening.
     */
    fun bargein() {
        if (isSpeaking) {
            Log.d(TAG, "Barge-in detected")
            isSpeaking = false
            transitionTo(ConversationState.Listening)
        }
    }

    /**
     * Build the prompt context for the LLM: last [MAX_TURNS] turns formatted as
     * role/content pairs. Inject this into your Gemini / GPT request.
     */
    fun buildPromptContext(): List<Map<String, String>> =
        _turns.value.takeLast(MAX_TURNS).map {
            mapOf("role" to it.role.label, "content" to it.text)
        }

    /** Most recent assistant response text, or null. */
    fun lastAssistantText(): String? =
        _turns.value.lastOrNull { it.role == Role.ASSISTANT }?.text

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun appendTurn(turn: ConversationTurn) {
        val current = _turns.value.toMutableList()
        current.add(turn)
        if (current.size > MAX_TURNS * 2) current.removeAt(0)
        _turns.value = current
    }

    private fun transitionTo(state: ConversationState) {
        _currentState.value = state
    }

    private fun newSessionId() = "session_${System.currentTimeMillis()}"
}
