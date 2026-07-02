package com.aladdin.app.recommendation

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RecommendationEngine — Item 71: Personalized recommendations from user history.
 */
@Singleton
class RecommendationEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object { private const val TAG = "RecommendationEngine" }

    data class Recommendation(val text: String, val confidence: Float, val category: String)

    private val actionHistory = ArrayDeque<String>(500)
    private val categoryFrequency = mutableMapOf<String, Int>()
    private val timePatterns = mutableMapOf<Int, MutableList<String>>() // hour -> actions

    fun recordAction(action: String, category: String = "general") {
        actionHistory.addFirst(action)
        if (actionHistory.size > 500) actionHistory.removeLast()
        categoryFrequency[category] = (categoryFrequency[category] ?: 0) + 1
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        timePatterns.getOrPut(hour) { mutableListOf() }.add(category)
    }

    fun getRecommendations(currentHour: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY), limit: Int = 5): List<Recommendation> {
        val recs = mutableListOf<Recommendation>()

        // Time-based patterns
        timePatterns[currentHour]?.let { hourActions ->
            val freq = hourActions.groupingBy { it }.eachCount()
            freq.entries.sortedByDescending { it.value }.take(3).forEach { (cat, count) ->
                val conf = (count.toFloat() / hourActions.size).coerceIn(0.1f, 0.95f)
                recs.add(Recommendation("Continue with $cat tasks (common at this hour)", conf, cat))
            }
        }

        // Frequency-based
        categoryFrequency.entries.sortedByDescending { it.value }.take(2).forEach { (cat, count) ->
            if (recs.none { it.category == cat }) {
                val conf = (count.toFloat() / actionHistory.size.coerceAtLeast(1)).coerceIn(0.1f, 0.8f)
                recs.add(Recommendation("You often use $cat", conf, cat))
            }
        }

        return recs.sortedByDescending { it.confidence }.take(limit)
    }

    fun getPersonalizationContext(): String {
        val topCats = categoryFrequency.entries.sortedByDescending { it.value }.take(3).map { it.key }
        return if (topCats.isEmpty()) "" else "User frequently uses: ${topCats.joinToString(", ")}."
    }
}
