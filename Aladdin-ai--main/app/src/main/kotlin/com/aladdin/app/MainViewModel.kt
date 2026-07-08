package com.aladdin.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aladdin.app.network.NetworkMonitor
import com.aladdin.assistant.orchestrator.JarvisOrchestrator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val text: String,
    val isUser: Boolean,
    val isError: Boolean = false
)

/** What action (if any) the "Retry"/action button on the error banner should do. */
enum class ErrorAction { NONE, OPEN_APP_SETTINGS, RETRY_MODEL_DOWNLOAD }

data class AladdinUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isListening: Boolean = false,
    val isThinking: Boolean = false,
    val isSpeaking: Boolean = false,
    val partialTranscript: String = "",
    val streamingResponse: String = "",
    val statusText: String = "Say \"Aladdin\" to wake",
    val downloadingModel: String? = null,
    val downloadProgress: Int = 0,
    val errorMessage: String? = null,
    val errorAction: ErrorAction = ErrorAction.NONE,
    val isOffline: Boolean = false
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val orchestrator: JarvisOrchestrator,
    private val networkMonitor: NetworkMonitor
) : ViewModel() {

    private val _uiState = MutableStateFlow(AladdinUiState())
    val uiState: StateFlow<AladdinUiState> = _uiState.asStateFlow()

    /** Re-runs the last thing that failed, set by whoever raised the error. */
    var onRetryModelDownload: (() -> Unit)? = null

    val status: StateFlow<String> = _uiState.map { it.statusText }.stateIn(
        viewModelScope, SharingStarted.Lazily, "Ready"
    )
    val response: StateFlow<String> = _uiState.map { it.messages.lastOrNull()?.text ?: "" }.stateIn(
        viewModelScope, SharingStarted.Lazily, ""
    )

    init {
        observeOrchestrator()
        observeNetwork()
    }

    private fun observeNetwork() {
        networkMonitor.startMonitoring()
        viewModelScope.launch {
            networkMonitor.state.collect { net ->
                _uiState.update { it.copy(isOffline = !net.isConnected) }
            }
        }
    }

    private fun observeOrchestrator() {
        viewModelScope.launch {
            orchestrator.assistantStateFlow.collect { state ->
                val statusText = when (state) {
                    JarvisOrchestrator.AssistantState.IDLE -> "Ready"
                    JarvisOrchestrator.AssistantState.WAKE_WORD_LISTENING -> "Say \"Aladdin\" to wake"
                    JarvisOrchestrator.AssistantState.LISTENING -> "Listening..."
                    JarvisOrchestrator.AssistantState.PROCESSING -> "Thinking..."
                    JarvisOrchestrator.AssistantState.SPEAKING -> "Speaking..."
                    JarvisOrchestrator.AssistantState.AGENT_PROCESSING -> "Processing with agents..."
                }
                _uiState.update {
                    it.copy(
                        statusText = statusText,
                        isListening = state == JarvisOrchestrator.AssistantState.LISTENING,
                        isThinking = state == JarvisOrchestrator.AssistantState.PROCESSING ||
                                state == JarvisOrchestrator.AssistantState.AGENT_PROCESSING,
                        isSpeaking = state == JarvisOrchestrator.AssistantState.SPEAKING
                    )
                }
            }
        }

        viewModelScope.launch {
            orchestrator.partialTranscriptFlow.collect { partial ->
                _uiState.update { it.copy(partialTranscript = partial) }
            }
        }

        viewModelScope.launch {
            orchestrator.streamingResponseFlow.collect { tokens ->
                _uiState.update { it.copy(streamingResponse = tokens) }
            }
        }

        viewModelScope.launch {
            orchestrator.messagesFlow.collect { messages ->
                val chatMessages = messages.map { msg ->
                    ChatMessage(
                        id = msg.id.hashCode().toLong(),
                        text = msg.text,
                        isUser = msg.role == "user"
                    )
                }
                _uiState.update { it.copy(messages = chatMessages) }
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        orchestrator.submitText(text)
    }

    fun startListening() {
        orchestrator.onMicButtonPressed()
    }

    fun stopListening() {
        orchestrator.onMicButtonPressed()
    }

    fun clearConversation() {
        _uiState.update {
            it.copy(messages = emptyList(), streamingResponse = "", partialTranscript = "")
        }
    }

    fun onSpeakingFinished() {
        _uiState.update { it.copy(isSpeaking = false) }
    }

    fun onBargeIn() {
        orchestrator.onMicButtonPressed()
        _uiState.update { it.copy(isSpeaking = false, partialTranscript = "", streamingResponse = "") }
    }

    fun setDownloadProgress(modelName: String, percent: Int) {
        _uiState.update { it.copy(downloadingModel = modelName, downloadProgress = percent) }
    }

    fun clearDownloadProgress() {
        _uiState.update { it.copy(downloadingModel = null, downloadProgress = 0) }
    }

    fun setError(message: String, action: ErrorAction = ErrorAction.NONE) {
        _uiState.update { it.copy(errorMessage = message, errorAction = action) }
        // Reliability: don't let a stale banner sit forever blocking the UI —
        // auto-dismiss non-actionable errors after a while, but leave anything
        // with an actual button (Settings/Retry) up until the user handles it.
        if (action == ErrorAction.NONE) {
            viewModelScope.launch {
                delay(8000)
                _uiState.update {
                    if (it.errorMessage == message) it.copy(errorMessage = null) else it
                }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null, errorAction = ErrorAction.NONE) }
    }

    fun retryLastAction() {
        val action = _uiState.value.errorAction
        dismissError()
        if (action == ErrorAction.RETRY_MODEL_DOWNLOAD) {
            onRetryModelDownload?.invoke()
        }
    }
}
