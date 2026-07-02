package com.aladdin.assistant.viewmodel

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aladdin.assistant.data.model.*
import com.aladdin.assistant.data.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val dataStore: DataStore<Preferences>
) : ViewModel() {

    companion object {
        val SETTINGS_KEY = stringPreferencesKey("app_settings")
        val ACTIVE_CONVERSATION_KEY = stringPreferencesKey("active_conversation_id")
    }

    // ─── State flows ──────────────────────────────────────────────────────────
    private val _voiceState = MutableStateFlow(VoiceState.IDLE)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _voiceAmplitude = MutableStateFlow(0f)
    val voiceAmplitude: StateFlow<Float> = _voiceAmplitude.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val settings: StateFlow<AppSettings> = dataStore.data
        .map { prefs ->
            val json = prefs[SETTINGS_KEY] ?: return@map AppSettings()
            try { Json.decodeFromString<AppSettings>(json) } catch (e: Exception) { AppSettings() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val conversations: StateFlow<List<Conversation>> = _searchQuery
        .debounce(300)
        .flatMapLatest { query ->
            if (query.isBlank()) chatRepository.getAllConversations()
            else chatRepository.searchConversations(query)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeMessages: StateFlow<List<ChatMessage>> = _activeConversationId
        .filterNotNull()
        .flatMapLatest { id -> chatRepository.getMessages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ─── Actions ──────────────────────────────────────────────────────────────
    fun setSearchQuery(query: String) { _searchQuery.value = query }

    fun setVoiceState(state: VoiceState) { _voiceState.value = state }

    fun setVoiceAmplitude(amp: Float) { _voiceAmplitude.value = amp }

    fun selectConversation(id: String) {
        _activeConversationId.value = id
        viewModelScope.launch {
            dataStore.edit { it[ACTIVE_CONVERSATION_KEY] = id }
        }
    }

    fun startNewConversation() {
        viewModelScope.launch {
            val conv = chatRepository.createConversation()
            selectConversation(conv.id)
        }
    }

    fun sendUserMessage(content: String) {
        val convId = _activeConversationId.value ?: return
        viewModelScope.launch {
            chatRepository.sendMessage(convId, content, MessageRole.USER)
            _isLoading.value = true
            _voiceState.value = VoiceState.PROCESSING
            // Simulated AI response (replace with real AI call)
            kotlinx.coroutines.delay(1200)
            val aiReply = generateMockResponse(content)
            chatRepository.sendMessage(convId, aiReply, MessageRole.ASSISTANT)
            _isLoading.value = false
            _voiceState.value = VoiceState.IDLE
        }
    }

    fun pinConversation(id: String, pinned: Boolean) {
        viewModelScope.launch { chatRepository.pinConversation(id, pinned) }
    }

    fun archiveConversation(id: String) {
        viewModelScope.launch { chatRepository.archiveConversation(id, true) }
    }

    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch { chatRepository.deleteConversation(conversation) }
    }

    fun updateSettings(newSettings: AppSettings) {
        viewModelScope.launch {
            val json = Json.encodeToString(AppSettings.serializer(), newSettings)
            dataStore.edit { it[SETTINGS_KEY] = json }
        }
    }

    private fun generateMockResponse(input: String): String {
        return when {
            input.contains("hello", ignoreCase = true) ||
            input.contains("hi", ignoreCase = true) ->
                "Hello! I'm Aladdin, your AI assistant. How can I help you today?"
            input.contains("weather", ignoreCase = true) ->
                "I'd need access to a weather service to give you current conditions. Would you like me to set that up?"
            input.contains("time", ignoreCase = true) ->
                "The current time is ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}."
            input.contains("help", ignoreCase = true) ->
                "I can help you with conversations, reminders, information lookup, and more. What do you need?"
            else -> "I understand your request: \"$input\". Let me process that for you. This is a sample response — connect your preferred AI provider in Settings for real answers."
        }
    }
}
