package com.aladdin.engine.models

import kotlinx.serialization.Serializable

// ─── Intent ───────────────────────────────────────────────────────────────────

enum class IntentType {
    QUESTION_ANSWERING, FACTUAL_LOOKUP, WEATHER_QUERY, NEWS_QUERY,
    SET_REMINDER, SEND_MESSAGE, PLAY_MUSIC, SEARCH_WEB, OPEN_APP, NAVIGATE,
    REMEMBER_FACT, RECALL_MEMORY,
    CREATE_PLAN, TRACK_GOAL, UPDATE_PROJECT,
    SMALL_TALK, CLARIFICATION_REQUEST,
    UNKNOWN
}

@Serializable
data class ClassifiedIntent(
    val type: IntentType,
    val confidence: Float,
    val entities: Map<String, String> = emptyMap(),
    val rawQuery: String = ""
)

// ─── Task / HTN ───────────────────────────────────────────────────────────────

enum class TaskStatus { PENDING, IN_PROGRESS, COMPLETED, FAILED, BLOCKED, SKIPPED }
enum class TaskPriority { LOW, NORMAL, HIGH, CRITICAL }

@Serializable
data class Task(
    val id: String,
    val name: String,
    val description: String = "",
    val type: TaskType = TaskType.PRIMITIVE,
    val status: TaskStatus = TaskStatus.PENDING,
    val priority: TaskPriority = TaskPriority.NORMAL,
    val parentId: String? = null,
    val subtaskIds: List<String> = emptyList(),
    /** Phase 4 #5 — explicit dependency IDs (must complete before this task). */
    val dependsOnIds: List<String> = emptyList(),
    val toolId: String? = null,
    val parameters: Map<String, String> = emptyMap(),
    val result: String? = null,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class TaskType { PRIMITIVE, COMPOUND }

// ─── Plan ─────────────────────────────────────────────────────────────────────

@Serializable
data class Plan(
    val id: String,
    val goal: String,
    val intent: IntentType,
    val tasks: List<Task>,
    val status: PlanStatus = PlanStatus.PENDING,
    val progress: Float = 0f,
    val reasoning: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class PlanStatus { PENDING, EXECUTING, COMPLETED, FAILED, CANCELLED }

// ─── Goal (Phase 4 #5 & #6 — priority, long-term, deadlines, reminders) ──────

/** Phase 4 #5 — goal-level priority separate from task priority. */
enum class GoalPriority { LOW, NORMAL, HIGH, CRITICAL }
enum class GoalStatus    { ACTIVE, PAUSED, COMPLETED, ABANDONED }

@Serializable
data class Goal(
    val id: String,
    val title: String,
    val description: String = "",
    val status: GoalStatus = GoalStatus.ACTIVE,
    val priority: GoalPriority = GoalPriority.NORMAL,
    val progress: Float = 0f,
    val milestones: List<Milestone> = emptyList(),
    val planIds: List<String> = emptyList(),
    /** Phase 4 #6 — long-term planning fields */
    val isLongTerm: Boolean = false,
    val deadlineMs: Long? = null,
    val reminderIntervalMs: Long? = null,
    val nextReminderMs: Long? = null,
    val weeklyTargetDays: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class Milestone(
    val id: String,
    val title: String,
    val isDone: Boolean = false,
    val priority: TaskPriority = TaskPriority.NORMAL,
    val completedAt: Long? = null
)

// ─── Tool ─────────────────────────────────────────────────────────────────────

data class ToolDefinition(
    val id: String,
    val name: String,
    val description: String,
    val intents: Set<IntentType>,
    val parameters: List<ToolParameter>,
    val requiresConfirmation: Boolean = false,
    val timeoutMs: Long = 10_000
)

data class ToolParameter(
    val name: String,
    val description: String,
    val type: String = "string",
    val required: Boolean = true,
    val defaultValue: String? = null
)

/** Phase 4 #9 — [isRecovered] flag signals graceful-degradation result. */
data class ToolResult(
    val toolId: String,
    val success: Boolean,
    val output: String = "",
    val error: String? = null,
    val durationMs: Long = 0,
    val isRecovered: Boolean = false
)

// ─── Conversation ─────────────────────────────────────────────────────────────

enum class MessageRole { USER, ASSISTANT, SYSTEM, TOOL }

@Serializable
data class ConversationMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val intentType: IntentType? = null,
    val toolId: String? = null,
    val planId: String? = null,
    val tokenCount: Int = estimateTokens(content),
    val timestampMs: Long = System.currentTimeMillis()
) {
    companion object {
        fun estimateTokens(text: String): Int = (text.split("\\s+".toRegex()).size * 1.3).toInt()
    }
}

// ─── Reasoning (Phase 4 #1, #7, #10) ─────────────────────────────────────────

data class ReasoningStep(
    val index: Int,
    val thought: String,
    val action: String? = null,
    val observation: String? = null,
    val isFinal: Boolean = false
)

/** Phase 4 additions: [needsClarification], [toolSequence]. */
data class ReasoningChain(
    val query: String,
    val steps: List<ReasoningStep>,
    val conclusion: String,
    val confidence: Float = 0.8f,
    /** Phase 4 #7 — true when confidence is below clarification threshold. */
    val needsClarification: Boolean = false,
    /** Phase 4 #10 — ordered list of tools the reasoner planned to use. */
    val toolSequence: List<String> = emptyList()
)

// ─── Phase 4 #7 — Confidence Score ───────────────────────────────────────────

/**
 * Structured confidence report returned by [ConfidenceScorer].
 */
data class ConfidenceScore(
    val overall: Float,                    // 0.0 – 1.0 weighted aggregate
    val signalBreakdown: Map<String, Float>,
    val needsClarification: Boolean,
    val clarificationQuestion: String? = null
)

// ─── Engine State ─────────────────────────────────────────────────────────────

data class AIEngineState(
    val isReady: Boolean = false,
    val isProcessing: Boolean = false,
    val currentPlan: Plan? = null,
    val currentGoal: Goal? = null,
    val activeTaskId: String? = null,
    val conversationLength: Int = 0,
    val lastError: AIError? = null
)

data class AIError(
    val code: AIErrorCode,
    val message: String,
    val recoverable: Boolean = true,
    val cause: Throwable? = null
)

enum class AIErrorCode {
    LLM_UNAVAILABLE, LLM_RATE_LIMITED, TOOL_NOT_FOUND, TOOL_EXECUTION_FAILED,
    PLAN_GENERATION_FAILED, INTENT_CLASSIFICATION_FAILED,
    MAX_RETRIES_EXCEEDED, CONTEXT_OVERFLOW, UNKNOWN
}

// ─── AI Engine Config ─────────────────────────────────────────────────────────

data class AIEngineConfig(
    val llmProvider: LLMProvider = LLMProvider.GEMINI,
    val geminiApiKey: String = "",
    val ollamaBaseUrl: String = "http://127.0.0.1:11434",
    val ollamaModel: String = "mistral",
    val maxContextTokens: Int = 4096,
    val maxRetries: Int = 3,
    val baseRetryDelayMs: Long = 500,
    val maxRetryDelayMs: Long = 16_000,
    val autoExecute: Boolean = true,
    val maxPlanDepth: Int = 5,
    val selfReflectionEnabled: Boolean = true,
    val chainOfThoughtEnabled: Boolean = true,
    val temperature: Float = 0.7f,
    /** Phase 4 #7 — clarification threshold below which AI asks for more info. */
    val clarificationThreshold: Float = 0.55f
)

enum class LLMProvider { GEMINI, OLLAMA, STUB }
