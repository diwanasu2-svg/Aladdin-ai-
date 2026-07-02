# Phase 3: Jarvis-Level Memory System — Implementation Guide

## Overview

Phase 3 transforms Aladdin into a Jarvis-level AI with human-like long-term memory,
semantic search, cross-session continuity, habit prediction, and relationship understanding.

## New Files Added

### smart-memory module: `src/main/kotlin/com/aladdin/memory/phase3/`

| File | Feature | Description |
|------|---------|-------------|
| `MemoryRouter.kt` | #3 Unified Memory Router | Routes queries across ALL memory stores automatically |
| `GoalManager.kt` | #4 Cross-Session Goals | Goals survive app close/restart, track progress |
| `ProactiveRecall.kt` | #5 Proactive Memory Recall | AI injects memories without user asking |
| `MemoryDecay.kt` | #6 Memory Decay | Ebbinghaus curve decay, duplicate merging |
| `HabitLearning.kt` | #7 Habit Learning | Detect routines, predict future actions |
| `RelationshipGraph.kt` | #8 Relationship Graph | Graph of people/places/events/organizations |
| `UserProfileEvolution.kt` | #9 User Profile Evolution | Learn preferences, music, food, language |
| `MemoryAnalytics.kt` | #10 Memory Analytics | Complete stats, benchmarks, duplicate reports |

### smart-memory module: `src/main/kotlin/com/aladdin/memory/db/entity/`

| File | Description |
|------|-------------|
| `Phase3Entities.kt` | GoalEntity, HabitEntity, RelationshipEntity — Room database entities |

### smart-memory module: `src/main/kotlin/com/aladdin/memory/db/dao/`

| File | Description |
|------|-------------|
| `Phase3Daos.kt` | GoalDao, HabitDao, RelationshipDao — database access objects |

### smart-memory module: `src/main/kotlin/com/aladdin/memory/engine/`

| File | Description |
|------|-------------|
| `EmbeddingPersistenceCache.kt` | #1 Persistent embedding cache — disk-backed, no regeneration on restart |

### ai-engine module: `src/main/kotlin/com/aladdin/engine/orchestrator/`

| File | Description |
|------|-------------|
| `JarvisOrchestrator.kt` | Master controller tying all Phase 3 systems together |

## Updated Files

| File | Changes |
|------|---------|
| `smart-memory/src/main/kotlin/com/aladdin/memory/db/MemoryDatabase.kt` | v2: adds goals, habits, relationships tables with safe v1→v2 migration |
| `smart-memory/src/main/kotlin/com/aladdin/memory/di/MemoryModule.kt` | Hilt providers for all Phase 3 systems |
| `smart-memory/build.gradle.kts` | Added TFLite GPU delegate for faster MiniLM inference |

## Architecture

```
User Message
     │
     ▼
JarvisOrchestrator.onUserMessage()
     │
     ├─► ProactiveRecall.recall()          ← Injects relevant memories automatically
     │         │
     │         ├─► Semantic similarity search
     │         ├─► Contact mention detection
     │         ├─► Upcoming reminder check
     │         └─► Session continuity
     │
     ├─► MemoryRouter.route()              ← Routes to correct memory stores
     │         │
     │         ├─► Short-term (conversations)
     │         ├─► Long-term (facts/preferences)
     │         ├─► Contacts
     │         ├─► Projects
     │         ├─► Locations
     │         ├─► Goals
     │         ├─► Habits
     │         └─► Relationships
     │
     ├─► UserProfileEvolution.learnFromMessage()   ← Background learning
     ├─► HabitLearning.recordAction()              ← Detect patterns
     └─► RelationshipGraph.detectMentions()        ← Extract relationships
     │
     ▼
AI Response with full memory context
```

## Feature Verification

### 1. Vector Embeddings Persist
- `EmbeddingEngine` stores embeddings in Room DB (`embedding` column in `memories` table)
- On warm-up, `MemoryRepository.warmUp()` loads all embeddings from DB into `VectorStore`
- `EmbeddingPersistenceCache` provides disk-level caching for computed vectors
- Embeddings are NEVER regenerated on restart — loaded from DB

### 2. Real MiniLM Embeddings
- `EmbeddingEngine` uses `all-MiniLM-L6-v2` TFLite model (384-dim)
- Model loaded from `context.filesDir/models/minilm/model.tflite`
- Falls back to BoW if model not downloaded (functional but less accurate)
- Download via: `scripts/download_models.sh`

### 3. Unified Memory Router
- `MemoryRouter.route(query)` automatically classifies intent and searches correct stores
- Returns `UnifiedMemoryResult` with memories, contacts, locations, goals, habits, relationships
- `buildContextString()` formats results for LLM injection

### 4. Cross-Session Goals
- Goals stored in `goals` table (persists across app restarts)
- `GoalManager.buildResumeContext()` generates session-start briefing
- Status: ACTIVE → PAUSED → COMPLETED/CANCELLED
- `JarvisOrchestrator.onSessionStart()` automatically surfaces interrupted goals

### 5. Proactive Memory Recall
- `ProactiveRecall.recall()` called before every AI response
- Runs 5 parallel recall strategies: semantic, contact, reminders, preferences, session
- `buildInjectionPrompt()` formats for system prompt injection

### 6. Memory Decay
- `MemoryDecay.runDecayPass()` implements Ebbinghaus forgetting curve
- Fast decay for low-importance, slow decay for high-importance memories
- Protected types (PREFERENCE, FACT, SUMMARY) never drop below floor
- Duplicate detection via cosine similarity (threshold 0.93)

### 7. Habit Learning
- `HabitLearning.recordAction()` clusters actions by time-of-day + day-of-week
- Promotes candidates to confirmed habits at ≥3 occurrences + ≥0.6 confidence
- `predictNow()` returns habits likely to occur in next N minutes
- `buildDailySummary()` generates today's routine overview

### 8. Relationship Graph
- Directed graph: `fromName → [relationType] → toName`
- `traverse()` supports multi-hop BFS up to configurable depth
- `findPath()` finds shortest connection between any two nodes
- `buildRelationshipContext()` formats for LLM injection

### 9. User Profile Evolution
- Extracts preferences from natural language ("I love pizza", "I hate horror")
- Auto-detects language (Hindi, Arabic, English)
- Decays stale preferences to prevent accumulation of outdated data
- `buildPersonalizationContext()` generates system prompt supplement

### 10. Memory Analytics
- `generateReport()` produces comprehensive MemoryAnalyticsReport
- `buildReportString()` formats as readable text report
- Includes: counts by type, embedding quality, storage size, duplicates, access patterns
- `benchmarkSearch()` measures search latency

## Integration Guide

```kotlin
// In your Activity/ViewModel (Hilt-injected):
@Inject lateinit var orchestrator: JarvisOrchestrator

// Session start
val ctx = orchestrator.onSessionStart(sessionId)
systemPrompt += ctx.systemContextInjection

// Per message
val msgCtx = orchestrator.onUserMessage(userMessage, sessionId)
val aiPrompt = msgCtx.memoryContext + "\n\n" + userMessage

// After AI responds
orchestrator.onAIResponse(aiResponse, sessionId)

// Session end
orchestrator.onSessionEnd(sessionId)

// Nightly (WorkManager)
orchestrator.runNightlyMaintenance()
```

## Database Migration

The database automatically migrates from v1 → v2 when the app starts.
All existing data is preserved. New tables (goals, habits, relationships) start empty.

