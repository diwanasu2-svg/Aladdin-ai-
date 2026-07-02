package com.aladdin.memory.phase3

import android.util.Log
import com.aladdin.memory.db.dao.RelationshipDao
import com.aladdin.memory.db.entity.RelationshipEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Relationship Graph
 *
 * Stores and queries a graph of relationships between people, places, events,
 * and projects. Provides human-like contextual understanding.
 *
 * Examples:
 *   Rahul → FRIEND_OF → Priya
 *   Rahul → WORKS_AT → ABC Company
 *   ABC Company → LOCATED_IN → Delhi
 *   Priya → BIRTHDAY → March 15
 *
 * Supports:
 *   - Adding directed/bidirectional edges
 *   - Traversal (find all connections of a node)
 *   - Path finding (how is A connected to B?)
 *   - Context enrichment for memory recall
 */
@Singleton
class RelationshipGraph @Inject constructor(
    private val relationshipDao: RelationshipDao
) {
    companion object {
        private const val TAG = "RelationshipGraph"
        private const val MAX_TRAVERSE_DEPTH = 3
    }

    // ─── Relationship Types ───────────────────────────────────────────────────

    object RelationType {
        const val FRIEND_OF = "friend_of"
        const val FAMILY_OF = "family_of"
        const val COLLEAGUE_OF = "colleague_of"
        const val REPORTS_TO = "reports_to"
        const val WORKS_AT = "works_at"
        const val LIVES_IN = "lives_in"
        const val KNOWS = "knows"
        const val MARRIED_TO = "married_to"
        const val PARENT_OF = "parent_of"
        const val CHILD_OF = "child_of"
        const val SIBLING_OF = "sibling_of"
        const val MANAGES = "manages"
        const val PART_OF = "part_of"
        const val LOCATED_IN = "located_in"
        const val ASSOCIATED_WITH = "associated_with"
        const val BIRTHDAY = "birthday"
        const val ANNIVERSARY = "anniversary"
    }

    // ─── Node Types ───────────────────────────────────────────────────────────

    object NodeType {
        const val PERSON = "person"
        const val ORGANIZATION = "organization"
        const val LOCATION = "location"
        const val EVENT = "event"
        const val PROJECT = "project"
    }

    // ─── Add Edges ────────────────────────────────────────────────────────────

    /**
     * Add a directed relationship: [fromName] → [relationType] → [toName].
     * If [bidirectional] is true, also adds the inverse edge.
     */
    suspend fun addRelationship(
        fromName: String,
        fromType: String = NodeType.PERSON,
        relationType: String,
        toName: String,
        toType: String = NodeType.PERSON,
        notes: String = "",
        bidirectional: Boolean = false
    ): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        // Check if already exists
        val existing = relationshipDao.find(fromName, relationType, toName)
        if (existing != null) {
            val updated = existing.copy(notes = notes, updatedAt = now)
            relationshipDao.update(updated)
            Log.d(TAG, "Relationship updated: $fromName →[$relationType]→ $toName")
            return@withContext existing.id
        }

        val entity = RelationshipEntity(
            fromName = fromName,
            fromType = fromType,
            relationType = relationType,
            toName = toName,
            toType = toType,
            notes = notes,
            strength = 0.5f,
            createdAt = now,
            updatedAt = now
        )
        val id = relationshipDao.insert(entity)

        if (bidirectional) {
            val inverse = inverseRelationType(relationType)
            if (inverse != null) {
                val invEntity = entity.copy(
                    id = 0,
                    fromName = toName,
                    fromType = toType,
                    toName = fromName,
                    toType = fromType,
                    relationType = inverse
                )
                relationshipDao.insert(invEntity)
            }
        }

        Log.i(TAG, "Relationship added: $fromName →[$relationType]→ $toName (id=$id)")
        id
    }

    // ─── Query Graph ─────────────────────────────────────────────────────────

    fun observeAll(): Flow<List<RelationshipEntity>> = relationshipDao.observeAll()

    suspend fun getRelationshipsOf(name: String): List<RelationshipEdge> = withContext(Dispatchers.IO) {
        val outgoing = relationshipDao.getOutgoing(name)
        val incoming = relationshipDao.getIncoming(name)
        (outgoing + incoming).map { it.toEdge() }.distinctBy { "${it.fromName}${it.relationType}${it.toName}" }
    }

    suspend fun getOutgoing(name: String): List<RelationshipEdge> = withContext(Dispatchers.IO) {
        relationshipDao.getOutgoing(name).map { it.toEdge() }
    }

    suspend fun getIncoming(name: String): List<RelationshipEdge> = withContext(Dispatchers.IO) {
        relationshipDao.getIncoming(name).map { it.toEdge() }
    }

    /**
     * Get all relationships relevant to the query (searches fromName and toName).
     */
    suspend fun getRelationshipsForQuery(query: String): List<RelationshipEdge> = withContext(Dispatchers.IO) {
        val terms = query.lowercase().split("\\s+".toRegex())
            .filter { it.length > 2 }
        val all = relationshipDao.getAll()
        all.filter { rel ->
            terms.any { term ->
                rel.fromName.lowercase().contains(term) ||
                    rel.toName.lowercase().contains(term) ||
                    rel.relationType.lowercase().contains(term) ||
                    rel.notes.lowercase().contains(term)
            }
        }.map { it.toEdge() }
    }

    // ─── Graph Traversal ──────────────────────────────────────────────────────

    /**
     * Find all nodes reachable from [startName] up to [maxDepth] hops.
     * Returns a list of RelationshipEdge paths.
     */
    suspend fun traverse(
        startName: String,
        maxDepth: Int = MAX_TRAVERSE_DEPTH
    ): GraphTraversalResult = withContext(Dispatchers.IO) {
        val visited = mutableSetOf<String>()
        val edges = mutableListOf<RelationshipEdge>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(Pair(startName, 0))
        visited.add(startName.lowercase())

        while (queue.isNotEmpty()) {
            val (current, depth) = queue.removeFirst()
            if (depth >= maxDepth) continue

            val outgoing = relationshipDao.getOutgoing(current)
            for (rel in outgoing) {
                edges.add(rel.toEdge())
                if (!visited.contains(rel.toName.lowercase())) {
                    visited.add(rel.toName.lowercase())
                    queue.add(Pair(rel.toName, depth + 1))
                }
            }
        }

        GraphTraversalResult(
            rootNode = startName,
            edges = edges.distinctBy { "${it.fromName}${it.relationType}${it.toName}" },
            nodesVisited = visited.size
        )
    }

    /**
     * Find the shortest path between two nodes (BFS).
     * Returns null if no path found.
     */
    suspend fun findPath(fromName: String, toName: String): List<RelationshipEdge>? = withContext(Dispatchers.IO) {
        if (fromName.equals(toName, ignoreCase = true)) return@withContext emptyList()

        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<List<RelationshipEdge>>()
        queue.add(emptyList())
        visited.add(fromName.lowercase())

        var currentFrom = fromName
        val pathMap = mutableMapOf<String, List<RelationshipEdge>>()
        pathMap[fromName.lowercase()] = emptyList()

        // BFS
        val bfsQueue = ArrayDeque<String>()
        bfsQueue.add(fromName)

        while (bfsQueue.isNotEmpty()) {
            val current = bfsQueue.removeFirst()
            val currentPath = pathMap[current.lowercase()] ?: continue

            val outgoing = relationshipDao.getOutgoing(current)
            for (rel in outgoing) {
                val nextKey = rel.toName.lowercase()
                if (!visited.contains(nextKey)) {
                    val newPath = currentPath + rel.toEdge()
                    pathMap[nextKey] = newPath
                    if (nextKey == toName.lowercase()) {
                        return@withContext newPath
                    }
                    visited.add(nextKey)
                    bfsQueue.add(rel.toName)
                }
            }
        }
        null
    }

    // ─── Context Builder ──────────────────────────────────────────────────────

    /**
     * Build a natural language description of relationships for [name].
     * Used for context injection into AI responses.
     */
    suspend fun buildRelationshipContext(name: String): String = withContext(Dispatchers.IO) {
        val edges = getRelationshipsOf(name)
        if (edges.isEmpty()) return@withContext ""

        val sb = StringBuilder("$name's connections:\n")
        edges.take(15).forEach { edge ->
            val direction = if (edge.fromName.equals(name, ignoreCase = true))
                "→ ${edge.relationType.replace('_', ' ')} → ${edge.toName}"
            else
                "← ${edge.relationType.replace('_', ' ')} ← ${edge.fromName}"
            sb.appendLine("  $direction${if (edge.notes.isNotBlank()) " (${edge.notes})" else ""}")
        }
        sb.toString().trim()
    }

    // ─── Stats ────────────────────────────────────────────────────────────────

    suspend fun getGraphStats(): RelationshipGraphStats = withContext(Dispatchers.IO) {
        val all = relationshipDao.getAll()
        val nodes = (all.map { it.fromName } + all.map { it.toName }).distinct()
        RelationshipGraphStats(
            totalEdges = all.size,
            totalNodes = nodes.size,
            mostConnected = nodes.maxByOrNull { node ->
                all.count { it.fromName == node || it.toName == node }
            } ?: "",
            relationshipTypes = all.map { it.relationType }.distinct()
        )
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun inverseRelationType(type: String): String? = when (type) {
        RelationType.FRIEND_OF -> RelationType.FRIEND_OF
        RelationType.FAMILY_OF -> RelationType.FAMILY_OF
        RelationType.MARRIED_TO -> RelationType.MARRIED_TO
        RelationType.SIBLING_OF -> RelationType.SIBLING_OF
        RelationType.PARENT_OF -> RelationType.CHILD_OF
        RelationType.CHILD_OF -> RelationType.PARENT_OF
        RelationType.MANAGES -> RelationType.REPORTS_TO
        RelationType.REPORTS_TO -> RelationType.MANAGES
        else -> null
    }

    private fun RelationshipEntity.toEdge() = RelationshipEdge(
        id = id,
        fromName = fromName,
        fromType = fromType,
        relationType = relationType,
        toName = toName,
        toType = toType,
        notes = notes,
        strength = strength
    )
}

data class RelationshipEdge(
    val id: Long = 0,
    val fromName: String,
    val fromType: String = "person",
    val relationType: String,
    val toName: String,
    val toType: String = "person",
    val notes: String = "",
    val strength: Float = 0.5f
)

data class GraphTraversalResult(
    val rootNode: String,
    val edges: List<RelationshipEdge>,
    val nodesVisited: Int
)

data class RelationshipGraphStats(
    val totalEdges: Int,
    val totalNodes: Int,
    val mostConnected: String,
    val relationshipTypes: List<String>
)
