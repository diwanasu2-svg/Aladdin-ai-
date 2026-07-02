# Aladdin AI Intelligence Engine — Android Module

Autonomous AI orchestration engine for Android. Kotlin + Coroutines + Hilt + Gemini/Ollama.

## Architecture

```
ai-engine/
├── models/
│   └── Models.kt              — All data models: Intent, Task, Plan, Goal, Tool, Message, State
├── intent/
│   └── IntentClassifier.kt    — 18-class intent classification (rule-based + LLM fallback)
├── planner/
│   ├── HTNPlanner.kt          — Hierarchical Task Network planner
│   ├── TaskDecomposer.kt      — Intent-to-task decomposition methods
│   └── GoalTracker.kt         — Goal + plan lifecycle tracking via StateFlow
├── tools/
│   ├── ToolRegistry.kt        — 30+ built-in tool definitions + custom registration
│   └── ToolExecutor.kt        — Tool dispatch with exponential backoff retry
├── reasoning/
│   ├── ReasoningEngine.kt     — Chain-of-thought (ReAct: Thought→Action→Observation)
│   └── SelfReflector.kt       — Response quality evaluation + auto-correction
├── llm/
│   ├── LLMClient.kt           — Gemini SDK + Ollama HTTP bridge + Stub
│   └── ContextManager.kt      — Conversation history + token-budget trimming
├── autonomy/
│   └── AutonomyEngine.kt      — Autonomous plan execution + replanning + retry
├── engine/
│   └── AIEngine.kt            — Main orchestrator (StateFlow state management)
└── di/
    └── AIEngineModule.kt      — Hilt SingletonComponent bindings
```

## Pipeline

```
User Input
  → IntentClassifier (rule-based + LLM fallback)
  → ReasoningEngine (Chain-of-Thought)
  → HTNPlanner (goal → task hierarchy)
  → AutonomyEngine (execute tasks autonomously)
    └── ToolExecutor (dispatch → retry → backoff)
    └── HTNPlanner.replan (on failure)
  → SelfReflector (quality check + auto-correct)
  → ContextManager (history update + trim)
  → StateFlow response emitted
```

## Supported Intents (18)

| Intent | Description |
|---|---|
| `QUESTION_ANSWERING` | General Q&A |
| `FACTUAL_LOOKUP` | Who/what/when/where queries |
| `WEATHER_QUERY` | Current & forecast weather |
| `NEWS_QUERY` | Top headlines & filtered news |
| `SET_REMINDER` | Timed reminders via AlarmManager |
| `SEND_MESSAGE` | SMS / WhatsApp / email |
| `PLAY_MUSIC` | Track, artist, playlist playback |
| `SEARCH_WEB` | Web search + result extraction |
| `OPEN_APP` | Launch apps by name |
| `NAVIGATE` | Turn-by-turn navigation |
| `REMEMBER_FACT` | Store to long-term memory |
| `RECALL_MEMORY` | Hybrid semantic + keyword recall |
| `CREATE_PLAN` | HTN plan generation for complex goals |
| `TRACK_GOAL` | Goal + milestone tracking |
| `UPDATE_PROJECT` | Project status and progress |
| `SMALL_TALK` | Conversational replies |
| `CLARIFICATION_REQUEST` | Clarify ambiguous requests |
| `UNKNOWN` | Fallback |

## Tools (30+)

Each tool maps to an Android action. Register real handlers via `engine.registerTool()`:

```kotlin
engine.registerTool("weather.fetch") { _, params ->
    weatherService.getCurrent(params["location"] ?: "current")
}

engine.registerTool("reminder.create") { _, params ->
    reminderRepo.add(ReminderEntity(title = params["subject"] ?: "", ...))
    "Reminder created"
}

engine.registerTool("memory.search") { _, params ->
    val results = memoryRepo.search(params["query"] ?: "")
    results.joinToString("\n") { it.memory.content }
}
```

Unregistered tools use stubs automatically in development.

## Setup

### 1. Add module

`settings.gradle.kts`:
```kotlin
include(":ai-engine")
```

`app/build.gradle.kts`:
```kotlin
implementation(project(":ai-engine"))
kapt("com.google.dagger:hilt-compiler:2.51.1")
```

### 2. Application class

```kotlin
@HiltAndroidApp
class MyApp : Application()
```

### 3. Initialize

```kotlin
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var aiEngine: AIEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize with Gemini
        aiEngine.init(AIEngineConfig(
            llmProvider = LLMProvider.GEMINI,
            geminiApiKey = BuildConfig.GEMINI_API_KEY,
            autoExecute = true,
            selfReflectionEnabled = true,
            chainOfThoughtEnabled = true,
            temperature = 0.7f
        ))

        // OR: use local Ollama
        aiEngine.init(AIEngineConfig(
            llmProvider = LLMProvider.OLLAMA,
            ollamaBaseUrl = "http://192.168.1.100:11434",
            ollamaModel = "mistral"
        ))

        // Observe responses
        lifecycleScope.launch {
            aiEngine.response.collect { msg ->
                showMessageToUser(msg.content)
            }
        }

        // Observe state
        lifecycleScope.launch {
            aiEngine.state.collect { state ->
                progressBar.isVisible = state.isProcessing
                state.lastError?.let { showError(it.message) }
            }
        }

        // Observe active plans
        lifecycleScope.launch {
            aiEngine.planFlow.collect { plans ->
                updatePlanUI(plans)
            }
        }
    }
}
```

### 4. Process queries

```kotlin
// Simple query
lifecycleScope.launch {
    val response = aiEngine.process("What's the weather in Tokyo?")
    println(response.content)
}

// With goal tracking
val goal = aiEngine.createGoal("Learn Kotlin", "Master Kotlin in 3 months")
lifecycleScope.launch {
    aiEngine.process(
        userInput = "Create a Kotlin learning plan",
        goalId = goal.id,
        onProgress = { taskName, fraction ->
            progressBar.progress = (fraction * 100).toInt()
            statusText.text = "Working on: $taskName"
        }
    )
}

// New session
aiEngine.newSession()

// Register real tool handlers
aiEngine.registerTool("alarm.schedule") { _, params ->
    val triggerAt = params["trigger_at"]?.toLongOrNull() ?: return@registerTool "Error"
    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
    "Alarm scheduled"
}
```

## LLM Configuration

| Provider | Best for | Requirements |
|---|---|---|
| `GEMINI` | Production | `GEMINI_API_KEY` in BuildConfig |
| `OLLAMA` | Privacy / offline | Ollama server on local network |
| `STUB` | Development / testing | None |

## State Flow API

```kotlin
engine.state.collect { s: AIEngineState ->
    s.isReady          // engine initialized
    s.isProcessing     // actively processing
    s.currentPlan      // active HTN plan
    s.currentGoal      // active goal
    s.activeTaskId     // currently executing task
    s.conversationLength // number of messages
    s.lastError        // last error (if any)
}

engine.planFlow.collect { plans: List<Plan> -> }
engine.goalFlow.collect { goals: List<Goal> -> }
engine.response.collect { msg: ConversationMessage -> }
```

## Key Design Decisions

- **StateFlow everywhere** — no callbacks, all state exposed as flows
- **Stub-first tools** — all 30+ tools work immediately in dev with realistic stubs
- **Two-stage intent** — fast rule-based first, LLM only for ambiguous cases
- **Exponential backoff** — `min(base × 2^attempt, maxDelay)` per task and per LLM call
- **Self-reflection** — every response is quality-scored before delivery; auto-corrected if below threshold
- **ReAct reasoning** — Thought → Action → Observation chain logged with each response

## Running Tests

```bash
./gradlew :ai-engine:test
```
