package com.aladdin.memory.phase3

import android.util.Log
import com.aladdin.memory.db.dao.ContactDao
import com.aladdin.memory.db.dao.LocationDao
import com.aladdin.memory.db.dao.ProjectDao
import com.aladdin.memory.db.dao.ReminderDao
import com.aladdin.memory.db.entity.ContactEntity
import com.aladdin.memory.db.entity.LocationEntity
import com.aladdin.memory.db.entity.MemoryType
import com.aladdin.memory.db.entity.ProjectEntity
import com.aladdin.memory.db.entity.ReminderEntity
import com.aladdin.memory.model.Memory
import com.aladdin.memory.model.MemorySearchResult
import com.aladdin.memory.model.SearchFilter
import com.aladdin.memory.repository.MemoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified Memory Router — single access point over ALL memory databases.
 *
 * Automatically routes queries to the correct memory stores, merges results,
 * and returns the best unified answer. Supports:
 *   - Short-term conversation memories
 *   - Long-term fact memories
 *   - Contacts
 *   - Preferences
 *   - Projects
 *   - Locations
 *   - Goals
 *   - Habits
 *   - Relationships
 *
 * The router classifies query intent and decides which stores to search,
 * then merges and ranks results from multiple sources.
 */
@Singleton
class MemoryRouter @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val contactDao: ContactDao,
    private val projectDao: ProjectDao,
    private val reminderDao: ReminderDao,
    private val locationDao: LocationDao,
    private val goalManager: GoalManager,
    private val habitLearning: HabitLearning,
    private val relationshipGraph: RelationshipGraph
) {

    companion object {
        private const val TAG = "MemoryRouter"
        private const val MAX_RESULTS_PER_STORE = 5
        private const val MAX_TOTAL_RESULTS = 15
    }

    // ─── Intent Classification ─────────────────────────────────────────────────

    enum class QueryIntent {
        PERSON,       // asking about a person/contact
        LOCATION,     // asking about a place
        PROJECT,      // asking about a task/project
        REMINDER,     // asking about reminders/schedule
        PREFERENCE,   // asking about user preferences
        GOAL,         // asking about goals/objectives
        HABIT,        // asking about routines/habits
        RELATIONSHIP, // asking about how people are related
        GENERAL       // general/unknown — search everywhere
    }

    private fun classifyIntent(query: String): QueryIntent {
        val q = query.lowercase()
        return when {
            q.containsAny("who is", "tell me about", "contact", "friend", "colleague",
                "family", "person", "someone", "anyone", "he said", "she said") -> QueryIntent.PERSON
            q.containsAny("where is", "location", "place", "address", "city", "office",
                "home", "restaurant", "shop", "nearby") -> QueryIntent.LOCATION
            q.containsAny("project", "task", "work on", "deadline", "milestone",
                "assignment", "ticket", "sprint") -> QueryIntent.PROJECT
            q.containsAny("remind", "reminder", "schedule", "appointment", "meeting",
                "alarm", "calendar", "due", "when is") -> QueryIntent.REMINDER
            q.containsAny("prefer", "like", "dislike", "favorite", "hate", "enjoy",
                "my preference", "settings") -> QueryIntent.PREFERENCE
            q.containsAny("goal", "objective", "aim", "target", "achieve", "accomplish",
                "want to", "plan to", "intend") -> QueryIntent.GOAL
            q.containsAny("habit", "routine", "daily", "every day", "always do",
                "usually", "typically", "schedule") -> QueryIntent.HABIT
            q.containsAny("relationship", "related to", "connected", "knows", "works with",
                "married to", "reports to", "friend of") -> QueryIntent.RELATIONSHIP
            else -> QueryIntent.GENERAL
        }
    }

    private fun String.containsAny(vararg keywords: String): Boolean =
        keywords.any { this.contains(it) }

    // ─── Unified Route ────────────────────────────────────────────────────────

    /**
     * Route [query] to the correct memory stores and return merged results.
     * This is the single entry point the AI uses for all memory lookups.
     */
    suspend fun route(query: String, maxResults: Int = MAX_TOTAL_RESULTS): UnifiedMemoryResult =
        withContext(Dispatchers.IO) {
            val intent = classifyIntent(query)
            Log.d(TAG, "Routing query: '$query' intent=$intent")

            val result = when (intent) {
                QueryIntent.PERSON -> routePersonQuery(query)
                QueryIntent.LOCATION -> routeLocationQuery(query)
                QueryIntent.PROJECT -> routeProjectQuery(query)
                QueryIntent.REMINDER -> routeReminderQuery(query)
                QueryIntent.PREFERENCE -> routePreferenceQuery(query)
                QueryIntent.GOAL -> routeGoalQuery(query)
                QueryIntent.HABIT -> routeHabitQuery(query)
                QueryIntent.RELATIONSHIP -> routeRelationshipQuery(query)
                QueryIntent.GENERAL -> routeGeneral(query)
            }

            Log.i(TAG, "Route result: ${result.memories.size} memories, " +
                "${result.contacts.size} contacts, intent=$intent")
            result.copy(memories = result.memories.take(maxResults))
        }

    // ─── Specialized Routes ───────────────────────────────────────────────────

    private suspend fun routePersonQuery(query: String): UnifiedMemoryResult {
        val (memories, contacts) = withContext(Dispatchers.IO) {
            val memAsync = async {
                memoryRepository.search(query, k = MAX_RESULTS_PER_STORE,
                    filter = SearchFilter(memoryTypes = setOf(MemoryType.CONVERSATION, MemoryType.FACT)))
            }
            val contactAsync = async { contactDao.search(extractPersonName(query), MAX_RESULTS_PER_STORE) }
            Pair(memAsync.await(), contactAsync.await())
        }
        val relationships = relationshipGraph.getRelationshipsForQuery(query)
        return UnifiedMemoryResult(
            memories = memories,
            contacts = contacts,
            relationships = relationships,
            intent = QueryIntent.PERSON
        )
    }

    private suspend fun routeLocationQuery(query: String): UnifiedMemoryResult {
        val (memories, locations) = withContext(Dispatchers.IO) {
            val memAsync = async {
                memoryRepository.search(query, k = MAX_RESULTS_PER_STORE,
                    filter = SearchFilter(memoryTypes = setOf(MemoryType.FACT, MemoryType.CONVERSATION)))
            }
            val locAsync = async { locationDao.search(query, MAX_RESULTS_PER_STORE) }
            Pair(memAsync.await(), locAsync.await())
        }
        return UnifiedMemoryResult(memories = memories, locations = locations, intent = QueryIntent.LOCATION)
    }

    private suspend fun routeProjectQuery(query: String): UnifiedMemoryResult {
        val (memories, projects) = withContext(Dispatchers.IO) {
            val memAsync = async {
                memoryRepository.search(query, k = MAX_RESULTS_PER_STORE,
                    filter = SearchFilter(memoryTypes = setOf(MemoryType.PROJECT, MemoryType.FACT)))
            }
            val projAsync = async { projectDao.observeAll() }
            Pair(memAsync.await(), emptyList<ProjectEntity>())
        }
        return UnifiedMemoryResult(memories = memories, projects = projects, intent = QueryIntent.PROJECT)
    }

    private suspend fun routeReminderQuery(query: String): UnifiedMemoryResult {
        val now = System.currentTimeMillis()
        val dayMs = 86_400_000L
        val (memories, reminders) = withContext(Dispatchers.IO) {
            val memAsync = async {
                memoryRepository.search(query, k = MAX_RESULTS_PER_STORE,
                    filter = SearchFilter(memoryTypes = setOf(MemoryType.REMINDER, MemoryType.EVENT)))
            }
            val remAsync = async { reminderDao.getDue(now, now + 7 * dayMs) }
            Pair(memAsync.await(), remAsync.await())
        }
        return UnifiedMemoryResult(memories = memories, reminders = reminders, intent = QueryIntent.REMINDER)
    }

    private suspend fun routePreferenceQuery(query: String): UnifiedMemoryResult {
        val memories = memoryRepository.search(
            query, k = MAX_RESULTS_PER_STORE,
            filter = SearchFilter(memoryTypes = setOf(MemoryType.PREFERENCE))
        )
        return UnifiedMemoryResult(memories = memories, intent = QueryIntent.PREFERENCE)
    }

    private suspend fun routeGoalQuery(query: String): UnifiedMemoryResult {
        val goals = goalManager.searchGoals(query)
        val memories = memoryRepository.search(query, k = MAX_RESULTS_PER_STORE,
            filter = SearchFilter(memoryTypes = setOf(MemoryType.FACT)))
        return UnifiedMemoryResult(memories = memories, goals = goals, intent = QueryIntent.GOAL)
    }

    private suspend fun routeHabitQuery(query: String): UnifiedMemoryResult {
        val habits = habitLearning.getHabitsForQuery(query)
        val memories = memoryRepository.search(query, k = MAX_RESULTS_PER_STORE)
        return UnifiedMemoryResult(memories = memories, habits = habits, intent = QueryIntent.HABIT)
    }

    private suspend fun routeRelationshipQuery(query: String): UnifiedMemoryResult {
        val relationships = relationshipGraph.getRelationshipsForQuery(query)
        val contacts = contactDao.search(query, MAX_RESULTS_PER_STORE)
        val memories = memoryRepository.search(query, k = MAX_RESULTS_PER_STORE,
            filter = SearchFilter(memoryTypes = setOf(MemoryType.FACT, MemoryType.CONVERSATION)))
        return UnifiedMemoryResult(
            memories = memories,
            contacts = contacts,
            relationships = relationships,
            intent = QueryIntent.RELATIONSHIP
        )
    }

    private suspend fun routeGeneral(query: String): UnifiedMemoryResult = withContext(Dispatchers.IO) {
        val memAsync = async { memoryRepository.search(query, k = MAX_RESULTS_PER_STORE * 2) }
        val contactAsync = async { contactDao.search(query, 3) }
        val locationAsync = async { locationDao.search(query, 3) }
        val goalAsync = async { goalManager.searchGoals(query) }

        val memories = memAsync.await()
        val contacts = contactAsync.await()
        val locations = locationAsync.await()
        val goals = goalAsync.await()

        UnifiedMemoryResult(
            memories = memories,
            contacts = contacts,
            locations = locations,
            goals = goals,
            intent = QueryIntent.GENERAL
        )
    }

    // ─── Context String Builder ────────────────────────────────────────────────

    /**
     * Build a context string from unified results suitable for LLM injection.
     */
    fun buildContextString(result: UnifiedMemoryResult): String {
        val sb = StringBuilder()

        if (result.memories.isNotEmpty()) {
            sb.appendLine("=== MEMORIES ===")
            result.memories.take(10).forEach { r ->
                sb.appendLine("• [${r.memory.memoryType}] ${r.memory.content.take(200)}")
            }
        }
        if (result.contacts.isNotEmpty()) {
            sb.appendLine("=== CONTACTS ===")
            result.contacts.forEach { c ->
                sb.appendLine("• ${c.name} (${c.relationshipType}) — ${c.notes.take(100)}")
            }
        }
        if (result.locations.isNotEmpty()) {
            sb.appendLine("=== LOCATIONS ===")
            result.locations.forEach { l ->
                sb.appendLine("• ${l.name}: ${l.address}")
            }
        }
        if (result.goals.isNotEmpty()) {
            sb.appendLine("=== ACTIVE GOALS ===")
            result.goals.take(5).forEach { g ->
                sb.appendLine("• [${g.status}] ${g.title} (${g.progressPercent}% done)")
            }
        }
        if (result.habits.isNotEmpty()) {
            sb.appendLine("=== HABITS ===")
            result.habits.take(5).forEach { h ->
                sb.appendLine("• ${h.description} @ ${h.timePattern}")
            }
        }
        if (result.relationships.isNotEmpty()) {
            sb.appendLine("=== RELATIONSHIPS ===")
            // RelationshipEdge exposes `relationType`, not `relationshipType`.
            result.relationships.take(5).forEach { r ->
                sb.appendLine("• ${r.fromName} → ${r.relationType} → ${r.toName}")
            }
        }
        if (result.reminders.isNotEmpty()) {
            sb.appendLine("=== UPCOMING REMINDERS ===")
            result.reminders.take(5).forEach { r ->
                sb.appendLine("• ${r.title}: ${r.body}")
            }
        }
        return sb.toString().trim()
    }

    private fun extractPersonName(query: String): String {
        val stopwords = setOf("who", "is", "tell", "me", "about", "the", "a", "an", "my", "our")
        return query.lowercase()
            .replace(Regex("[^a-z\\s]"), " ")
            .split("\\s+".toRegex())
            .filter { it !in stopwords && it.isNotBlank() }
            .joinToString(" ")
            .trim()
            .ifBlank { query }
    }
}

data class UnifiedMemoryResult(
    val memories: List<MemorySearchResult> = emptyList(),
    val contacts: List<ContactEntity> = emptyList(),
    val locations: List<LocationEntity> = emptyList(),
    val projects: List<ProjectEntity> = emptyList(),
    val reminders: List<ReminderEntity> = emptyList(),
    val goals: List<GoalRecord> = emptyList(),
    val habits: List<HabitRecord> = emptyList(),
    val relationships: List<RelationshipEdge> = emptyList(),
    val intent: MemoryRouter.QueryIntent = MemoryRouter.QueryIntent.GENERAL
)
