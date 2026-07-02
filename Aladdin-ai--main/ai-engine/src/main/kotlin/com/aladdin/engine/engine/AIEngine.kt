package com.aladdin.engine.engine

import android.util.Log
import com.aladdin.engine.autonomy.AutonomyEngine
import com.aladdin.engine.intent.IntentClassifier
import com.aladdin.engine.llm.ContextManager
import com.aladdin.engine.llm.LLMClient
import com.aladdin.engine.models.*
import com.aladdin.engine.planner.GoalTracker
import com.aladdin.engine.planner.HTNPlanner
import com.aladdin.engine.reasoning.ReasoningEngine
import com.aladdin.engine.reasoning.SelfReflector
import com.aladdin.engine.tools.ToolExecutor
import com.aladdin.engine.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aladdin AI Intelligence Engine — Main Orchestrator.
 *
 * Orchestrates the full pipeline:
 *   User Input
 *     → [IntentClassifier] (rule-based + LLM fallback)
 *     → [ReasoningEngine] (chain-of-thought)
 *     → [HTNPlanner] (hierarchical task decomposition)
 *     → [AutonomyEngine] (autonomous execution + retry)
 *     → [SelfReflector] (quality check + auto-correction)
 *     → [ContextManager] (history update)
 *     → Final Response (via StateFlow)
 *
 * State management via [StateFlow]:
 *   - [state]    — overall engine state
 *   - [response] — stream of AI responses
 *   - [planFlow] — active plan updates
 *   - [goalFlow] — active goal updates
 *
 * Usage:
 *   val engine = ... // injected via Hilt
 *   engine.init(AIEngineConfig(geminiApiKey = "..."))
 *   engine.process("Remind me to call Sarah in 2 hours")
 *   engine.response.collect { msg -> showToUser(msg) }
 */
@Singleton
class AIEngine @Inject constructor(
    private val intentClassifier: IntentClassifier,
    private val htnPlanner: HTNPlanner,
    private val autonomyEngine: AutonomyEngine,
    private val reasoningEngine: ReasoningEngine,
    private val selfReflector: SelfReflector,
    private val llmClient: LLMClient,
    private val contextManager: ContextManager,
    private val goalTracker: GoalTracker,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor
) {
    companion object {
        private const val TAG = "AIEngine"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ─── State ────────────────────────────────────────────────────────────────

    private val _state = MutableStateFlow(AIEngineState())
    val state: StateFlow<AIEngineState> = _state.asStateFlow()

    private val _response = MutableSharedFlow<ConversationMessage>(replay = 1)
    val response: SharedFlow<ConversationMessage> = _response.asSharedFlow()

    val planFlow: StateFlow<List<Plan>> = goalTracker.activePlans
    val goalFlow: StateFlow<List<Goal>> = goalTracker.activeGoals

    // ─── Initialization ───────────────────────────────────────────────────────

    /**
     * Initialize the engine with configuration.
     * Must be called before [process].
     */
    fun init(config: AIEngineConfig = AIEngineConfig()) {
        llmClient.init(config)
        contextManager.configure(config.maxContextTokens)
        contextManager.addSystemMessage(buildSystemPrompt())
        _state.value = _state.value.copy(isReady = true)
        Log.i(TAG, "AIEngine initialized: provider=${config.llmProvider} autoExec=${config.autoExecute}")
    }

    // ─── Main Entry Point ─────────────────────────────────────────────────────

    /**
     * Process a user message through the full pipeline.
     *
     * @param userInput   Raw user utterance
     * @param goalId      Associate with an existing goal (optional)
     * @param onProgress  Progress callback (taskName, fraction)
     */
    suspend fun process(
        userInput: String,
        goalId: String? = null,
        onProgress: ((String, Float) -> Unit)? = null
    ): ConversationMessage {
        if (!_state.value.isReady) {
            Log.w(TAG, "Engine not initialized — calling init() with defaults")
            init()
        }

        setState { copy(isProcessing = true, lastError = null) }
        Log.i(TAG, "Processing: '${userInput.take(60)}'")

        return try {
            val result = runPipeline(userInput, goalId, onProgress)
            setState { copy(isProcessing = false, conversationLength = contextManager.messageCount) }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline failed: ${e.message}", e)
            val error = AIError(AIErrorCode.UNKNOWN, e.message ?: "Unknown error")
            setState { copy(isProcessing = false, lastError = error) }
            errorResponse(userInput, e)
        }
    }

    /**
     * Start a new conversation session.
     */
    fun newSession() {
        contextManager.startNewSession()
        setState {
            copy(
                currentPlan = null,
                currentGoal = null,
                activeTaskId = null,
                conversationLength = 0
            )
        }
        Log.i(TAG, "New session started")
    }

    /**
     * Create a named goal and associate future plans with it.
     */
    fun createGoal(title: String, description: String = ""): Goal {
        val goal = goalTracker.createGoal(title, description)
        setState { copy(currentGoal = goal) }
        return goal
    }

    /**
     * Register a custom tool handler at runtime.
     */
    fun registerTool(toolId: String, handler: ToolExecutor.ToolHandler) {
        toolExecutor.registerHandler(toolId, handler)
    }

    /**
     * Get the current conversation history.
     */
    fun getConversationHistory(): List<ConversationMessage> =
        contextManager.getMessagesForLLM()

    /**
     * Get a summary of the current conversation for memory storage.
     */
    fun getConversationSummary(): String = contextManager.getSummaryText()

    /**
     * Persist and flush the current conversation context to durable storage.
     * Called by [AladdinBackgroundService] during background sync passes.
     */
    fun flushContext() {
        try {
            contextManager.flush()
            Log.i(TAG, "Context flushed (${contextManager.messageCount} messages)")
        } catch (e: Exception) {
            Log.w(TAG, "Context flush failed: ${e.message}")
        }
    }

    /**
     * Run lightweight background maintenance tasks (goal expiry, memory compaction).
     * Safe to call off the main thread.
     */
    fun runBackgroundMaintenance() {
        scope.launch {
            try {
                goalTracker.runMaintenance()
                Log.d(TAG, "Background maintenance complete")
            } catch (e: Exception) {
                Log.w(TAG, "Background maintenance error: ${e.message}")
            }
        }
    }

    // ─── Pipeline ─────────────────────────────────────────────────────────────

    private suspend fun runPipeline(
        userInput: String,
        goalId: String?,
        onProgress: ((String, Float) -> Unit)?
    ): ConversationMessage {

        // Step 1: Add user message to context
        val userMsg = contextManager.addUserMessage(userInput)

        // Step 2: Classify intent (rule-based first, LLM fallback for low-confidence)
        setState { copy(activeTaskId = "intent_classification") }
        val intent = classifyIntent(userInput)
        Log.d(TAG, "Intent: ${intent.type} (${intent.confidence})")

        // Step 3: Chain-of-thought reasoning (if enabled)
        val reasoning = try {
            val context = contextManager.buildRecentContext()
            reasoningEngine.reason(userInput, context, intent)
        } catch (e: Exception) {
            Log.w(TAG, "Reasoning failed: ${e.message}")
            reasoningEngine.reasonFast(userInput, intent)
        }

        // Step 4: Generate plan (HTN decomposition)
        setState { copy(activeTaskId = "planning") }
        val plan = htnPlanner.plan(
            goal = userInput,
            intent = intent,
            context = intent.entities + mapOf("sessionId" to contextManager.getSessionId())
        )
        goalTracker.trackPlan(plan, goalId)
        setState { copy(currentPlan = plan, activeTaskId = "executing") }

        // Step 5: Autonomous execution
        val execResult = autonomyEngine.execute(plan, goalId, onProgress)

        // Step 6: LLM final answer generation
        val finalResponse = generateFinalResponse(
            query = userInput,
            intent = intent,
            reasoning = reasoning,
            execResult = execResult
        )

        // Step 7: Self-reflection + auto-correction
        val (correctedResult, reflection) = autonomyEngine.reflectAndCorrect(
            execResult.copy(rawResponse = finalResponse),
            userInput
        )
        val finalAnswer = correctedResult.rawResponse

        // Step 8: Add assistant response to context
        val assistantMsg = contextManager.addAssistantMessage(finalAnswer, planId = plan.id)

        // Step 9: Emit response
        _response.emit(assistantMsg)

        setState {
            copy(
                activeTaskId = null,
                currentPlan = correctedResult.plan,
                conversationLength = contextManager.messageCount
            )
        }

        Log.i(TAG, "Pipeline complete: reflection.acceptable=${reflection.isAcceptable}")
        return assistantMsg
    }

    // ─── Intent Classification ────────────────────────────────────────────────

    private suspend fun classifyIntent(query: String): ClassifiedIntent {
        // Stage 1: fast rule-based
        val ruleIntent = intentClassifier.classify(query)
        if (ruleIntent.type != IntentType.UNKNOWN && ruleIntent.confidence >= 0.7f) {
            return ruleIntent
        }

        // Stage 2: LLM-powered classification
        return try {
            val json = llmClient.classifyIntent(query)
            val llmIntent = intentClassifier.classifyFromLLMJson(json, query)
            // Merge entities from both classifiers
            val mergedEntities = ruleIntent.entities + llmIntent.entities
            llmIntent.copy(entities = mergedEntities)
        } catch (e: Exception) {
            Log.w(TAG, "LLM intent classification failed: ${e.message} — using rule result")
            ruleIntent.copy(type = if (ruleIntent.type == IntentType.UNKNOWN) IntentType.QUESTION_ANSWERING else ruleIntent.type)
        }
    }

    // ─── Response Generation ──────────────────────────────────────────────────

    private suspend fun generateFinalResponse(
        query: String,
        intent: ClassifiedIntent,
        reasoning: com.aladdin.engine.models.ReasoningChain,
        execResult: com.aladdin.engine.autonomy.ExecutionResult
    ): String {
        // If execution produced a clear response, prefer it
        if (execResult.isSuccess && execResult.rawResponse.isNotBlank()
            && !execResult.rawResponse.contains("[stub]", ignoreCase = true)) {
            return execResult.rawResponse
        }

        // Otherwise generate via LLM with full context
        val messages = contextManager.getMessagesForLLM()
        val taskSummary = execResult.taskResults.values
            .filter { it.success }
            .joinToString("\n") { "- ${it.toolId}: ${it.output.take(100)}" }

        return try {
            val enrichedMessages = messages + ConversationMessage(
                role = MessageRole.SYSTEM,
                content = "Task execution results:\n$taskSummary\n\nReasoning:\n${reasoning.conclusion.take(200)}"
            )
            llmClient.chat(enrichedMessages)
        } catch (e: Exception) {
            Log.w(TAG, "LLM response generation failed: ${e.message}")
            execResult.rawResponse.ifBlank { reasoning.conclusion.ifBlank { fallbackResponse(intent) } }
        }
    }

    private fun fallbackResponse(intent: ClassifiedIntent): String = when (intent.type) {
        IntentType.SMALL_TALK           -> "Hello! How can I help you today?"
        IntentType.WEATHER_QUERY        -> "I'm having trouble fetching weather data right now."
        IntentType.SET_REMINDER         -> "I've noted that reminder for you."
        IntentType.SEND_MESSAGE         -> "I'll take care of sending that message."
        IntentType.NAVIGATE             -> "Navigation is being prepared."
        IntentType.PLAY_MUSIC           -> "Starting playback now."
        IntentType.REMEMBER_FACT        -> "I've stored that in my memory."
        IntentType.RECALL_MEMORY        -> "Let me search through what I remember..."
        IntentType.CREATE_PLAN          -> "I've created a plan to help you achieve that goal."
        IntentType.TRACK_GOAL           -> "Your goal has been recorded and I'll track your progress."
        else                            -> "I'm working on that for you."
    }

    private fun errorResponse(query: String, e: Exception): ConversationMessage {
        val msg = ConversationMessage(
            role = MessageRole.ASSISTANT,
            content = "I encountered an issue processing your request. Please try again."
        )
        scope.launch { _response.emit(msg) }
        return msg
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun buildSystemPrompt(): String = """
        You are Aladdin, an intelligent, autonomous personal AI assistant running on Android.
        You have access to: long-term memory, reminders, contacts, calendar, navigation, music, web search, and news.
        You think step-by-step before acting. You are proactive, helpful, and always complete tasks autonomously.
        You remember everything the user tells you. You adapt your tone to match the user's style.
        Today's date is ${java.text.SimpleDateFormat("EEEE, MMMM d, yyyy", java.util.Locale.US).format(java.util.Date())}.
    """.trimIndent()

    private fun setState(block: AIEngineState.() -> AIEngineState) {
        _state.update { it.block() }
    }
}
