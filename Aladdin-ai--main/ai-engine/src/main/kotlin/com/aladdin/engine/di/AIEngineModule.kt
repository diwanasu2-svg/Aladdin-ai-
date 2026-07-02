package com.aladdin.engine.di

import com.aladdin.engine.autonomy.AutonomyEngine
import com.aladdin.engine.engine.AIEngine
import com.aladdin.engine.intent.IntentClassifier
import com.aladdin.engine.llm.ContextManager
import com.aladdin.engine.llm.LLMClient
import com.aladdin.engine.models.AIEngineConfig
import com.aladdin.engine.models.LLMProvider
import com.aladdin.engine.planner.GoalTracker
import com.aladdin.engine.planner.HTNPlanner
import com.aladdin.engine.planner.TaskDecomposer
import com.aladdin.engine.reasoning.ConfidenceScorer
import com.aladdin.engine.reasoning.ReasoningEngine
import com.aladdin.engine.reasoning.SelfReflector
import com.aladdin.engine.tools.ToolExecutor
import com.aladdin.engine.tools.ToolRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt DI module for the AI Intelligence Engine.
 *
 * All components are singletons — the engine maintains stateful
 * conversation context, goal tracking, and in-memory indexes.
 *
 * To configure the engine at startup:
 *   engine.init(AIEngineConfig(geminiApiKey = BuildConfig.GEMINI_API_KEY))
 *
 * To switch LLM provider at runtime:
 *   engine.init(AIEngineConfig(llmProvider = LLMProvider.OLLAMA))
 */
@Module
@InstallIn(SingletonComponent::class)
object AIEngineModule {

    @Provides
    @Singleton
    fun provideDefaultConfig(): AIEngineConfig = AIEngineConfig(
        llmProvider = LLMProvider.STUB,    // replaced at runtime via engine.init()
        maxContextTokens = 4096,
        maxRetries = 3,
        autoExecute = true,
        selfReflectionEnabled = true,
        chainOfThoughtEnabled = true
    )

    @Provides
    @Singleton
    fun provideLLMClient(config: AIEngineConfig): LLMClient = LLMClient(config)

    @Provides
    @Singleton
    fun provideIntentClassifier(): IntentClassifier = IntentClassifier()

    @Provides
    @Singleton
    fun provideToolRegistry(): ToolRegistry = ToolRegistry()

    @Provides
    @Singleton
    fun provideToolExecutor(toolRegistry: ToolRegistry): ToolExecutor = ToolExecutor(toolRegistry)

    @Provides
    @Singleton
    fun provideTaskDecomposer(): TaskDecomposer = TaskDecomposer()

    @Provides
    @Singleton
    fun provideHTNPlanner(
        toolRegistry: ToolRegistry,
        taskDecomposer: TaskDecomposer
    ): HTNPlanner = HTNPlanner(toolRegistry, taskDecomposer)

    @Provides
    @Singleton
    fun provideGoalTracker(): GoalTracker = GoalTracker()

    @Provides
    @Singleton
    fun provideReasoningEngine(llmClient: LLMClient): ReasoningEngine = ReasoningEngine(llmClient)

    @Provides
    @Singleton
    fun provideSelfReflector(llmClient: LLMClient): SelfReflector = SelfReflector(llmClient)

    @Provides
    @Singleton
    fun provideConfidenceScorer(): ConfidenceScorer = ConfidenceScorer()

    @Provides
    @Singleton
    fun provideContextManager(llmClient: LLMClient): ContextManager = ContextManager(llmClient)

    @Provides
    @Singleton
    fun provideAutonomyEngine(
        toolExecutor: ToolExecutor,
        htnPlanner: HTNPlanner,
        goalTracker: GoalTracker,
        selfReflector: SelfReflector
    ): AutonomyEngine = AutonomyEngine(toolExecutor, htnPlanner, goalTracker, selfReflector)

    @Provides
    @Singleton
    fun provideAIEngine(
        intentClassifier: IntentClassifier,
        htnPlanner: HTNPlanner,
        autonomyEngine: AutonomyEngine,
        reasoningEngine: ReasoningEngine,
        selfReflector: SelfReflector,
        llmClient: LLMClient,
        contextManager: ContextManager,
        goalTracker: GoalTracker,
        toolRegistry: ToolRegistry,
        toolExecutor: ToolExecutor
    ): AIEngine = AIEngine(
        intentClassifier, htnPlanner, autonomyEngine, reasoningEngine,
        selfReflector, llmClient, contextManager, goalTracker, toolRegistry, toolExecutor
    )
}
