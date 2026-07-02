# Aladdin Smart Memory System – Android Module

Offline-first, long-term memory for Android AI assistants. Kotlin + Coroutines + Room + TFLite.

## Architecture

```
smart-memory/
├── db/
│   ├── entity/         — Room entities (Memory, UserProfile, Contact, Project, Reminder, Location, Summary)
│   ├── dao/            — DAOs with Flow + coroutines
│   └── MemoryDatabase  — Room DB with FTS5 full-text search
├── engine/
│   ├── EmbeddingEngine — MiniLM TFLite (384-dim) + BoW fallback
│   ├── VectorStore     — In-memory cosine similarity search + LSH
│   ├── BM25Engine      — BM25 keyword ranking with Porter stemming
│   ├── HybridSearch    — BM25 + semantic fusion (α configurable)
│   ├── ImportanceScorer— Frequency + recency + engagement + type bonus
│   └── MemorySummarizer— Extractive + LLM (Ollama bridge) summarization
├── repository/
│   ├── MemoryRepository       — Primary CRUD + search + forget
│   ├── UserProfileRepository  — Name, age, preferences
│   ├── ContactRepository      — Contacts + relationship scoring
│   ├── ProjectRepository      — Projects + progress tracking
│   ├── ReminderRepository     — Reminders + alarm management
│   └── LocationRepository     — Locations + visit frequency
├── worker/
│   ├── MemoryCleanupWorker    — Nightly LRU forgetting (7-day)
│   └── MemorySummaryWorker    — Nightly auto-summarization
├── di/
│   └── MemoryModule           — Hilt DI bindings
└── model/
    └── Models.kt              — Domain models + SearchFilter
```

## Features

| Feature | Implementation |
|---|---|
| Storage | SQLite + Room ORM |
| Full-text search | FTS5 virtual table + BM25 ranking |
| Semantic search | MiniLM-L6-v2 TFLite embeddings (384-dim) |
| Vector store | In-memory cosine similarity + LSH for >5k entries |
| Hybrid search | BM25 + semantic with configurable α weight |
| Importance score | Frequency + recency decay + engagement + type bonus |
| Summarization | Extractive (fallback) + Ollama/LLM bridge |
| Forgetting | LRU 7-day + importance threshold (auto, via WorkManager) |
| Context compression | Token-budget compressor for LLM context injection |
| User profile | Name, age, topics, music, food, language |
| Contacts | Relationship scoring (increments on interaction) |
| Projects | Status, progress %, milestones, due dates |
| Reminders | Exact alarms, repeat intervals, contact links |
| Locations | Visit frequency tracking, lat/lon |
| DI | Hilt |
| Background tasks | WorkManager periodic workers |
| Offline-first | 100% — no network required |

## Setup

### 1. Add module

`settings.gradle.kts`:
```kotlin
include(":smart-memory")
```

`app/build.gradle.kts`:
```kotlin
implementation(project(":smart-memory"))
kapt("com.google.dagger:hilt-compiler:2.51.1")
```

### 2. Application class

```kotlin
@HiltAndroidApp
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Schedule background workers
        MemoryCleanupWorker.schedule(this)
        MemorySummaryWorker.schedule(this)
    }
}
```

### 3. Inject and use

```kotlin
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var memoryRepo: MemoryRepository
    @Inject lateinit var profileRepo: UserProfileRepository
    @Inject lateinit var contactRepo: ContactRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        lifecycleScope.launch {
            // Warm up indexes at startup
            memoryRepo.warmUp()
        }
    }
}
```

### 4. Core operations

```kotlin
// Add a memory
val id = memoryRepo.addMemory(NewMemory(
    content = "The user's name is Alex and they love hiking",
    memoryType = MemoryType.FACT,
    tags = listOf("name", "hobby"),
    sessionId = currentSessionId
))

// Hybrid search
val results = memoryRepo.search(
    query = "What does the user like to do outdoors?",
    k = 5,
    filter = SearchFilter(memoryTypes = setOf(MemoryType.FACT, MemoryType.PREFERENCE))
)
results.forEach { r ->
    println("${r.memory.content} (score=${r.score})")
}

// Get context for LLM
val context = memoryRepo.getContextMemories(
    query = userMessage,
    maxTokens = 1024
)
// Inject `context` into your LLM prompt

// User profile
profileRepo.updateName("Alex")
profileRepo.addFavoriteTopic("hiking")
profileRepo.addFavoriteMusic("jazz")

// Contacts
val contactId = contactRepo.add(ContactEntity(
    name = "Sarah",
    relationshipType = "friend",
    notes = "Loves coffee and photography"
))
contactRepo.recordInteraction(contactId) // bumps relationship score

// Projects
val projectId = projectRepo.add(ProjectEntity(
    title = "Android Voice Assistant",
    description = "Building Aladdin",
    status = ProjectStatus.ACTIVE
))
projectRepo.updateProgress(projectId, 65)

// Reminders
val remId = reminderRepo.add(ReminderEntity(
    title = "Call Sarah",
    triggerAt = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(2)
))
val overdue = reminderRepo.getOverdue()
```

## Search Details

### Hybrid Search Scoring

```
final_score = α × semantic_score + (1-α) × bm25_normalized_score
```

Default α = 0.6 (semantic-leaning). For exact fact lookup, use α = 0.2.

```kotlin
memoryRepo.search(query, filter = SearchFilter(
    memoryTypes = setOf(MemoryType.FACT),
    minImportance = 0.5f,
    fromMs = weekAgoMs
))
```

### Importance Formula

```
score = 0.30 × freq_score
      + 0.35 × recency_score
      + 0.20 × engagement_score
      + 0.15 × type_bonus
```

- **Frequency**: log-scale access count (max=100)
- **Recency**: exponential decay, half-life = 3 days
- **Engagement**: tag richness + content length proxy
- **Type bonus**: FACT=1.0, PREFERENCE=0.9, PROJECT=0.8, CONVERSATION=0.3

### Forgetting (LRU 7-day)

Memories are forgotten if **both** conditions are true:
1. Not accessed in 7+ days
2. Importance score < 0.25

Run manually or via nightly `MemoryCleanupWorker`.

## Embedding Model

Download the MiniLM TFLite model:

```bash
# Download model + vocab
curl -L https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/model.tflite \
     -o models/minilm/model.tflite
curl -L https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/vocab.txt \
     -o models/minilm/vocab.txt

# Push to device
adb push models/minilm/ /data/data/YOUR.APP.ID/files/models/minilm/
```

Without the model, the system uses a BoW (bag-of-words) fallback automatically.

## Running Tests

```bash
# Unit tests (no device required)
./gradlew :smart-memory:test

# Instrumented tests (requires connected device/emulator)
./gradlew :smart-memory:connectedAndroidTest
```

## License

Apache 2.0
