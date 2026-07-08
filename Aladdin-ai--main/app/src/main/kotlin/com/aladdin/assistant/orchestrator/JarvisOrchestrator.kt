package com.aladdin.assistant.orchestrator

import android.content.Context
import android.util.Log
import com.aladdin.assistant.agent.AgentCommunication
import com.aladdin.assistant.agent.AgentPrioritySystem
import com.aladdin.assistant.agent.BrowserAgent
import com.aladdin.assistant.agent.CodingAgent
import com.aladdin.assistant.agent.CoordinatorAgent
import com.aladdin.assistant.agent.MemoryAgent
import com.aladdin.assistant.agent.PlannerAgent
import com.aladdin.assistant.agent.ResearchAgent
import com.aladdin.assistant.agent.SafetyAgent
import com.aladdin.assistant.agent.VisionAgent
import com.aladdin.assistant.bargein.BargeInManager
import com.aladdin.assistant.llm.StreamingLLM
import com.aladdin.assistant.noise.RNNoise
import com.aladdin.assistant.stt.StreamingSTT
import com.aladdin.assistant.stt.WhisperEngine
import com.aladdin.assistant.tts.StreamingTTS
import com.aladdin.assistant.vad.VADEngine
import com.aladdin.assistant.wake.WakeWordEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

/**
 * Phase 5 – Jarvis Orchestrator (Multi-Agent Edition)
 *
 * Extends Phase 2's streaming voice pipeline with a full Jarvis-level
 * Multi-Agent AI Team:
 *
 *   WakeWord → VAD → RNNoise → StreamingSTT
 *      ↓                            ↓
 *   BargeIn ←── StreamingTTS ← StreamingLLM
 *                                   ↑
 *              ┌─────────────────────┘
 *              │   CoordinatorAgent
 *              │   ├─ SafetyAgent   (priority 1 – highest)
 *              │   ├─ PlannerAgent  (priority 2)
 *              │   ├─ MemoryAgent   (priority 2)
 *              │   ├─ ResearchAgent (priority 3)
 *              │   ├─ BrowserAgent  (priority 3)
 *              │   ├─ CodingAgent   (priority 3)
 *              │   └─ VisionAgent   (priority 3)
 *              │
 *              AgentCommunication (shared bus)
 *              AgentPrioritySystem (task scheduler)
 */
@Singleton
class JarvisOrchestrator @Inject constructor(@ApplicationContext private val context: Context) {
    companion object {
        private const val TAG = "JarvisOrchestrator"
        // Model-quality pass (2026-07-08): the old prompt ("You are Aladdin, a fast
        // AI assistant. Respond concisely in 2-3 short sentences. No markdown.") gave
        // no personality, no guidance on handling ambiguity/uncertainty, and actively
        // discouraged the assistant from ever asking a clarifying question — all of
        // which made responses feel like a generic chatbot instead of a personal
        // assistant that actually understands context. Rewritten for a genuine
        // Jarvis-like feel while keeping voice-reply constraints (short, no markdown,
        // since this is read aloud by TTS, not displayed as rich text).
        private const val SYSTEM_PROMPT =
            "You are Aladdin — a sharp, personable voice assistant running on the user's own phone. " +
            "You are speaking out loud (TTS), not writing a document, so:\n" +
            "- Reply in plain conversational sentences. Never use markdown, bullet points, headers, or asterisks.\n" +
            "- Default to 1-3 short sentences. Only go longer when the user explicitly asks for detail, " +
            "a list, or an explanation — then it's fine to be thorough.\n" +
            "- Use the conversation history and any \"relevant context\" notes you're given to stay consistent " +
            "and avoid repeating yourself or re-asking things the user already told you.\n" +
            "- If a request is genuinely ambiguous and guessing wrong would waste the user's time, ask one short " +
            "clarifying question instead of assuming. Otherwise, just answer — don't ask permission to help.\n" +
            "- If you don't know something or can't do it (e.g. no internet/tool access right now), say so plainly " +
            "in one sentence rather than inventing an answer.\n" +
            "- Be warm and a little witty when it fits naturally, but never at the cost of being clear and useful."

        /** Heuristic — route to multi-agent team when input looks complex. */
        private val AGENT_TRIGGER_KEYWORDS = listOf(
            "research", "search", "find information", "look up",
            "write code", "generate code", "debug", "fix bug", "implement",
            "browse", "open website", "navigate to", "go to http",
            "remember", "save to memory", "what did i say", "recall",
            "analyze image", "read image", "what's in the picture", "ocr",
            "plan", "schedule", "organize", "step by step"
        )
    }

    // ── Assistant state ───────────────────────────────────────────────────────

    enum class AssistantState {
        IDLE, WAKE_WORD_LISTENING, LISTENING, PROCESSING, SPEAKING, AGENT_PROCESSING
    }

    private val _stateFlow = MutableStateFlow(AssistantState.IDLE)
    val assistantStateFlow: StateFlow<AssistantState> = _stateFlow.asStateFlow()

    private val _partialFlow = MutableStateFlow("")
    val partialTranscriptFlow: StateFlow<String> = _partialFlow.asStateFlow()

    private val _streamingFlow = MutableStateFlow("")
    val streamingResponseFlow: StateFlow<String> = _streamingFlow.asStateFlow()

    data class Message(val id: String, val role: String, val text: String)
    private val _messagesFlow = MutableStateFlow<List<Message>>(emptyList())
    val messagesFlow: StateFlow<List<Message>> = _messagesFlow.asStateFlow()

    /** Agent status for UI display */
    private val _agentStatusFlow = MutableStateFlow("")
    val agentStatusFlow: StateFlow<String> = _agentStatusFlow.asStateFlow()

    // ── Phase 2: Voice pipeline components ───────────────────────────────────

    private val whisperEngine  = WhisperEngine(context)
    private val vadEngine      = VADEngine()
    private val rnNoise        = RNNoise()
    private val streamingTts   = StreamingTTS(context)

    // Bug fix (2026-07-05): StreamingLLM used to be built with zero args, which
    // hardcoded it to a local Ollama server that most devices never actually run,
    // and any Gemini/OpenAI key set in Settings was silently ignored. Now it
    // reads the user's configured provider (Gemini API key preferred, else the
    // configured Ollama host/port/model) via ProviderConfig.
    private val providerConfig = com.aladdin.app.provider.ProviderConfig(context)
    // Bug fix (2026-07-07): pass Context so StreamingLLM can run the on-device
    // llama.cpp engine by default instead of requiring a local/remote Ollama
    // server — see StreamingLLM's class doc for the full provider priority.
    private val streamingLlm   = StreamingLLM(providerConfig, context)
    private val wakeWord       = WakeWordEngine(context)
    private val stt            = StreamingSTT(context, whisperEngine)
    private val bargeIn        = BargeInManager(vadEngine, streamingTts)

    // ── Phase 5: Multi-Agent Team ─────────────────────────────────────────────

    /** Priority scheduler — must be created first */
    val prioritySystem = AgentPrioritySystem()

    /** Safety Agent — highest priority (1) */
    val safetyAgent = SafetyAgent(prioritySystem)

    /** Memory Agent — high priority (2) */
    val memoryAgent = MemoryAgent()

    /** Research Agent — medium priority (3) */
    val researchAgent = ResearchAgent(safetyAgent, memoryAgent)

    /** Coding Agent — medium priority (3) */
    val codingAgent = CodingAgent(safetyAgent, memoryAgent)

    /** Browser Agent — medium priority (3) */
    val browserAgent = BrowserAgent(context, safetyAgent, memoryAgent)

    /** Vision Agent — medium priority (3) */
    val visionAgent = VisionAgent(context, memoryAgent, safetyAgent)

    /** Planner Agent — high priority (2) */
    val plannerAgent = PlannerAgent(memoryAgent, safetyAgent)

    /** Coordinator Agent — orchestrates all agents */
    val coordinatorAgent = CoordinatorAgent(
        context      = context,
        plannerAgent = plannerAgent,
        researchAgent = researchAgent,
        codingAgent  = codingAgent,
        memoryAgent  = memoryAgent,
        safetyAgent  = safetyAgent,
        browserAgent = browserAgent,
        visionAgent  = visionAgent,
        prioritySystem = prioritySystem
    )

    // ── Coroutine scope ───────────────────────────────────────────────────────

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var llmJob: Job? = null
    private var sttJob: Job? = null

    // ── Initialisation ────────────────────────────────────────────────────────

    suspend fun initialise() {
        // Phase 2: voice pipeline init (parallel)
        withContext(Dispatchers.IO) {
            val w = async { whisperEngine.initialise() }
            val k = async { wakeWord.initialise() }
            val n = async { rnNoise.initialise() }
            w.await(); k.await(); n.await()
        }

        // Phase 2: LLM + TTS setup
        streamingLlm.addSystemPrompt(SYSTEM_PROMPT)
        streamingTts.initialise { Log.d(TAG, "TTS ready") }

        streamingTts.speakingListener = object : StreamingTTS.SpeakingListener {
            override fun onSpeakingStarted(sentence: String) {
                bargeIn.onAssistantStartedSpeaking()
                _stateFlow.value = AssistantState.SPEAKING
            }
            override fun onSpeakingFinished() {}
            override fun onAllSentencesDone() {
                bargeIn.onAssistantStoppedSpeaking()
                _stateFlow.value = AssistantState.WAKE_WORD_LISTENING
                _streamingFlow.value = ""
                startWakeWordListening()
            }
        }

        bargeIn.listener = object : BargeInManager.BargeInListener {
            override fun onBargeIn() { handleBargeIn() }
        }

        // Phase 5: start all agents
        startAgentTeam()

        startWakeWordListening()
        Log.d(TAG, "JarvisOrchestrator Phase 5 ready — Multi-Agent Team active")
    }

    /** Start all Phase 5 agents. */
    private fun startAgentTeam() {
        prioritySystem.start()
        safetyAgent.start()
        memoryAgent.start()
        researchAgent.start()
        codingAgent.start()
        browserAgent.start()
        visionAgent.start()
        plannerAgent.start()
        coordinatorAgent.start()
        Log.d(TAG, "Agent team started: Safety(p1) > Planner/Memory(p2) > Research/Browser/Coding/Vision(p3)")
    }

    // ── Voice pipeline (Phase 2) ──────────────────────────────────────────────

    private fun startWakeWordListening() {
        _stateFlow.value = AssistantState.WAKE_WORD_LISTENING
        wakeWord.startListening()
        scope.launch {
            wakeWord.wakeWordFlow.collect { event ->
                if (event is WakeWordEngine.WakeWordEvent.Detected) {
                    wakeWord.stopListening(); startListening()
                }
            }
        }
    }

    private fun startListening() {
        _stateFlow.value = AssistantState.LISTENING
        _partialFlow.value = ""; stt.startStreaming()
        sttJob = scope.launch {
            stt.transcriptFlow.collect { event ->
                when (event) {
                    is StreamingSTT.TranscriptEvent.Partial -> _partialFlow.value = event.text
                    is StreamingSTT.TranscriptEvent.Final -> {
                        _partialFlow.value = ""; stt.stopStreaming()
                        if (event.text.isNotBlank()) onUtterance(event.text)
                        else startWakeWordListening()
                    }
                    is StreamingSTT.TranscriptEvent.Error -> {
                        stt.stopStreaming(); startWakeWordListening()
                    }
                }
            }
        }
    }

    // ── Utterance routing ─────────────────────────────────────────────────────

    /**
     * Route the transcribed utterance:
     *  - Complex / agentic requests → CoordinatorAgent (multi-agent team)
     *  - Simple conversational requests → StreamingLLM (Phase 2 fast path)
     */
    private fun onUtterance(text: String) {
        addMsg(Message(UUID.randomUUID().toString(), "user", text))
        _streamingFlow.value = ""
        streamingTts.resumeForNewUtterance()

        if (shouldUseAgentTeam(text)) {
            routeToAgentTeam(text)
        } else {
            routeToLlm(text)
        }
    }

    private fun shouldUseAgentTeam(text: String): Boolean {
        val lower = text.lowercase()
        return AGENT_TRIGGER_KEYWORDS.any { lower.contains(it) }
    }

    // ── Agent team routing ────────────────────────────────────────────────────

    private fun routeToAgentTeam(text: String) {
        _stateFlow.value = AssistantState.AGENT_PROCESSING
        _agentStatusFlow.value = "Agent team processing…"

        llmJob = scope.launch {
            try {
                val result = coordinatorAgent.coordinate(text)

                _agentStatusFlow.value = "Agents: ${result.agentsUsed.map { it.name }.joinToString(", ")}"
                _streamingFlow.value = result.finalResponse

                addMsg(Message(UUID.randomUUID().toString(), "assistant", result.finalResponse))
                streamingTts.resumeForNewUtterance()
                streamingTts.enqueueSentence(result.finalResponse)
                streamingTts.finishEnqueuing()

                // Save utterance pair to memory
                memoryAgent.save(
                    content = "User: $text\nAladdin: ${result.finalResponse}",
                    type = MemoryAgent.MemoryType.EPISODIC,
                    tags = listOf("conversation"),
                    importance = 0.6f
                )
            } catch (e: Exception) {
                Log.e(TAG, "Agent team error: ${e.message}")
                _agentStatusFlow.value = ""
                routeToLlm(text)   // Fallback to direct LLM
            }
        }
    }

    // ── Direct LLM routing (Phase 2 fast path) ────────────────────────────────

    private fun routeToLlm(text: String) {
        _stateFlow.value = AssistantState.PROCESSING
        val buffer = StringBuilder()
        // Model-quality fix (2026-07-08): the multi-agent path (CoordinatorAgent)
        // already pulls memoryAgent.semanticSearch() results into its reasoning, but
        // this fast conversational path never did — so anything Aladdin "remembered"
        // only actually surfaced for requests that happened to trigger the agent
        // team. Most normal chat (the majority of turns) went through here with zero
        // memory recall, so the assistant felt like it forgot everything between
        // unrelated topics. Now both paths use memory.
        val augmentedText = withRelevantMemory(text)
        llmJob = scope.launch {
            streamingLlm.streamMessage(augmentedText).collect { event ->
                when (event) {
                    is StreamingLLM.LlmEvent.Token -> {
                        buffer.append(event.text); _streamingFlow.value = buffer.toString()
                    }
                    is StreamingLLM.LlmEvent.SentenceComplete -> streamingTts.enqueueSentence(event.sentence)
                    is StreamingLLM.LlmEvent.FullResponse -> {
                        addMsg(Message(UUID.randomUUID().toString(), "assistant", event.text))
                        streamingTts.finishEnqueuing()
                        // Save to memory
                        memoryAgent.save(
                            content = "User: $text\nAladdin: ${event.text}",
                            type = MemoryAgent.MemoryType.SHORT_TERM,
                            tags = listOf("conversation"),
                            importance = 0.5f
                        )
                    }
                    is StreamingLLM.LlmEvent.Error -> {
                        // Bug fix (2026-07-05): this used to fail completely silently —
                        // no chat bubble, no toast, nothing — leaving the user staring
                        // at a screen that never replies. Now the failure reason is
                        // shown as an assistant message so it's actually visible.
                        Log.e(TAG, "LLM error: ${event.message}", event.cause)
                        addMsg(Message(
                            UUID.randomUUID().toString(),
                            "assistant",
                            "⚠️ Couldn't reach the AI: ${event.message}\n" +
                                "Add a Gemini API key in Settings (easiest — free at aistudio.google.com), " +
                                "or make sure Ollama is running locally."
                        ))
                        streamingTts.stopSpeaking()
                        startWakeWordListening()
                    }
                    is StreamingLLM.LlmEvent.Done  -> {}
                }
            }
        }
    }

    // ── Barge-in handling ─────────────────────────────────────────────────────

    private fun handleBargeIn() {
        llmJob?.cancel(); llmJob = null; sttJob?.cancel(); sttJob = null
        stt.stopStreaming(); _streamingFlow.value = ""; _partialFlow.value = ""
        _agentStatusFlow.value = ""
        startListening()
    }

    // ── Mic button ────────────────────────────────────────────────────────────

    fun onMicButtonPressed() {
        when (_stateFlow.value) {
            AssistantState.IDLE,
            AssistantState.WAKE_WORD_LISTENING -> { wakeWord.stopListening(); startListening() }
            AssistantState.LISTENING           -> { stt.stopStreaming(); startWakeWordListening() }
            AssistantState.SPEAKING            -> { bargeIn.onAssistantStoppedSpeaking(); streamingTts.stopSpeaking(); startListening() }
            AssistantState.AGENT_PROCESSING    -> { llmJob?.cancel(); _agentStatusFlow.value = ""; startWakeWordListening() }
            AssistantState.PROCESSING          -> {}
        }
    }

    /** Direct text input — skips voice pipeline (for typed queries or testing). */
    fun submitText(text: String) {
        if (text.isBlank()) return
        addMsg(Message(UUID.randomUUID().toString(), "user", text))
        _streamingFlow.value = ""
        if (shouldUseAgentTeam(text)) routeToAgentTeam(text)
        else routeToLlm(text)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun addMsg(m: Message) { _messagesFlow.value = _messagesFlow.value + m }

    /**
     * Prepends any relevant long/short-term memories to the user's text before it
     * goes to the LLM — the chat bubble still shows the user's original words
     * (added separately in onUtterance/submitText), this only affects what the
     * model actually sees. Fails silently to the plain text on any error so a
     * memory-lookup bug can never block the assistant from responding.
     */
    private fun withRelevantMemory(text: String): String {
        val relevant = try {
            memoryAgent.semanticSearch(text, topK = 3, minImportance = 0.3f)
        } catch (e: Exception) {
            Log.w(TAG, "Memory recall skipped: ${e.message}")
            emptyList()
        }
        if (relevant.isEmpty()) return text
        val memoryContext = relevant.joinToString("\n") { "- ${it.content}" }
        return "Relevant context from earlier (use only if it actually helps, don't repeat it verbatim):\n" +
            "$memoryContext\n\nUser: $text"
    }

    fun release() {
        scope.cancel()
        stt.release(); whisperEngine.release()
        wakeWord.release(); streamingTts.release(); rnNoise.release(); bargeIn.release()
        visionAgent.release()
        prioritySystem.stop()
        AgentCommunication.clearAllContext()
        AgentCommunication.clearLog()
    }

    // ── Agent inspection helpers (for debugging / UI) ─────────────────────────

    fun getMemoryStats() = memoryAgent.getStats()
    fun getSecurityLog() = safetyAgent.getSecurityLog()
    fun getPriorityQueueSize() = prioritySystem.queueSize()
    fun getAgentCommunicationLog() = AgentCommunication.getLog()
}
