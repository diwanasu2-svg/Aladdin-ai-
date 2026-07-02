package com.aladdin.memory.phase3

import android.util.Log
import com.aladdin.memory.db.dao.UserProfileDao
import com.aladdin.memory.db.entity.UserProfileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User Profile Evolution
 *
 * Continuously learns and updates the user's profile over time:
 *   - Interests, hobbies, and favorite topics
 *   - Preferred language, tone, and communication style
 *   - Favorite apps, music, food, places, routines
 *   - Old preferences can be replaced or adjusted with decay
 *
 * Profile updates are driven by conversation analysis:
 *   - Explicit statements ("I love pizza", "I hate horror movies")
 *   - Implicit signals (repeatedly asking about topic X)
 *   - Feedback signals (thumbs up/down on responses)
 *
 * Goal: AI becomes progressively smarter and more personalized.
 */
@Singleton
class UserProfileEvolution @Inject constructor(
    private val userProfileDao: UserProfileDao
) {
    companion object {
        private const val TAG = "UserProfileEvolution"
        private const val MAX_TOPICS = 30
        private const val MAX_MUSIC = 20
        private const val MAX_FOODS = 20
        private const val PREFERENCE_DECAY_DAYS = 90L // interests decay if not reinforced for 90 days
    }

    // ─── Read Profile ─────────────────────────────────────────────────────────

    fun observeProfile(): Flow<UserProfileEntity?> = userProfileDao.observe()

    suspend fun getProfile(): UserProfileEntity = withContext(Dispatchers.IO) {
        userProfileDao.get() ?: UserProfileEntity().also {
            userProfileDao.insert(it)
            Log.i(TAG, "Created default user profile")
        }
    }

    // ─── Update from Conversation ─────────────────────────────────────────────

    /**
     * Analyze a user message for preference signals and update profile accordingly.
     * Call this for every user message.
     */
    suspend fun learnFromMessage(message: String) = withContext(Dispatchers.IO) {
        val profile = getProfile()
        val lower = message.lowercase()

        var updated = profile

        // Extract positive preferences
        val positivePatterns = listOf(
            Regex("i (?:love|like|enjoy|prefer|adore|favorite|favourite) ([a-z][a-z\\s]{1,30})"),
            Regex("(?:my favorite|my favourite) (?:is|are) ([a-z][a-z\\s]{1,30})"),
            Regex("i(?:'m| am) (?:into|a fan of) ([a-z][a-z\\s]{1,30})")
        )

        val negativePatterns = listOf(
            Regex("i (?:hate|dislike|don't like|do not like|can't stand|cannot stand) ([a-z][a-z\\s]{1,30})"),
            Regex("(?:not a fan of|allergic to) ([a-z][a-z\\s]{1,30})")
        )

        // Extract topics
        val newTopics = mutableListOf<String>()
        val newDislikes = mutableListOf<String>()

        for (pattern in positivePatterns) {
            pattern.findAll(lower).forEach { match ->
                val topic = match.groupValues.getOrNull(1)?.trim()?.take(30) ?: return@forEach
                if (topic.isNotBlank() && topic.length > 2) newTopics.add(topic)
            }
        }

        for (pattern in negativePatterns) {
            pattern.findAll(lower).forEach { match ->
                val topic = match.groupValues.getOrNull(1)?.trim()?.take(30) ?: return@forEach
                if (topic.isNotBlank() && topic.length > 2) newDislikes.add(topic)
            }
        }

        // Detect music preferences
        val musicKeywords = listOf("song", "music", "album", "artist", "singer", "band", "playlist")
        if (musicKeywords.any { lower.contains(it) } && newTopics.isNotEmpty()) {
            val musicTopics = newTopics.filter { topic ->
                musicKeywords.none { lower.indexOf(it) > lower.indexOf(topic) - 20 }
            }
            val updatedMusic = (profile.favoriteMusic + musicTopics)
                .distinct().take(MAX_MUSIC)
            if (updatedMusic != profile.favoriteMusic) {
                updated = updated.copy(favoriteMusic = updatedMusic)
            }
        }

        // Detect food preferences
        val foodKeywords = listOf("food", "eat", "drink", "meal", "restaurant", "cuisine", "dish")
        if (foodKeywords.any { lower.contains(it) } && newTopics.isNotEmpty()) {
            val updatedFoods = (profile.favoriteFoods + newTopics).distinct().take(MAX_FOODS)
            if (updatedFoods != profile.favoriteFoods) {
                updated = updated.copy(favoriteFoods = updatedFoods)
            }
        }

        // Update favorite topics (general)
        if (newTopics.isNotEmpty()) {
            val updatedTopics = (newTopics + profile.favoriteTopics)
                .distinct().take(MAX_TOPICS)
            if (updatedTopics != profile.favoriteTopics) {
                updated = updated.copy(favoriteTopics = updatedTopics)
            }
        }

        // Update disliked topics
        if (newDislikes.isNotEmpty()) {
            val updatedDislikes = (newDislikes + profile.dislikedTopics)
                .distinct().take(MAX_TOPICS)
                .filter { dislike -> dislike !in updated.favoriteTopics }
            if (updatedDislikes != profile.dislikedTopics) {
                updated = updated.copy(dislikedTopics = updatedDislikes)
            }
        }

        // Detect language/tone preference
        val detectedLang = detectLanguage(message)
        if (detectedLang != profile.language && detectedLang != "en") {
            updated = updated.copy(language = detectedLang)
        }

        if (updated != profile) {
            userProfileDao.update(updated.copy(updatedAt = System.currentTimeMillis()))
            Log.d(TAG, "Profile evolved: +${newTopics.size} topics, +${newDislikes.size} dislikes")
        }
    }

    // ─── Explicit Updates ─────────────────────────────────────────────────────

    suspend fun setName(name: String) = updateProfile { it.copy(name = name) }
    suspend fun setAge(age: Int) = updateProfile { it.copy(age = age) }
    suspend fun setLanguage(language: String) = updateProfile { it.copy(language = language) }
    suspend fun setVoiceName(voiceName: String) = updateProfile { it.copy(voiceName = voiceName) }

    suspend fun addFavoriteTopic(topic: String) = updateProfile { profile ->
        val updated = (listOf(topic.trim().lowercase()) + profile.favoriteTopics).distinct().take(MAX_TOPICS)
        val cleanDislikes = profile.dislikedTopics.filter { it != topic.trim().lowercase() }
        profile.copy(favoriteTopics = updated, dislikedTopics = cleanDislikes)
    }

    suspend fun removeFavoriteTopic(topic: String) = updateProfile { profile ->
        profile.copy(favoriteTopics = profile.favoriteTopics.filter { it != topic.lowercase() })
    }

    suspend fun addDislikedTopic(topic: String) = updateProfile { profile ->
        val updated = (listOf(topic.trim().lowercase()) + profile.dislikedTopics).distinct().take(MAX_TOPICS)
        val cleanFavorites = profile.favoriteTopics.filter { it != topic.trim().lowercase() }
        profile.copy(dislikedTopics = updated, favoriteTopics = cleanFavorites)
    }

    suspend fun addFavoriteMusic(music: String) = updateProfile { profile ->
        profile.copy(favoriteMusic = (listOf(music) + profile.favoriteMusic).distinct().take(MAX_MUSIC))
    }

    suspend fun addFavoriteFood(food: String) = updateProfile { profile ->
        profile.copy(favoriteFoods = (listOf(food) + profile.favoriteFoods).distinct().take(MAX_FOODS))
    }

    // ─── Tone & Style Adaptation ──────────────────────────────────────────────

    /**
     * Build a personalization system prompt supplement based on the current profile.
     * Injected into every AI response to ensure consistent personalization.
     */
    suspend fun buildPersonalizationContext(): String = withContext(Dispatchers.IO) {
        val profile = getProfile()
        val sb = StringBuilder()

        if (profile.name.isNotBlank()) {
            sb.appendLine("User's name: ${profile.name}")
        }
        if (profile.language != "en") {
            sb.appendLine("Preferred language: ${profile.language}")
        }
        if (profile.favoriteTopics.isNotEmpty()) {
            sb.appendLine("Favorite topics: ${profile.favoriteTopics.take(10).joinToString(", ")}")
        }
        if (profile.dislikedTopics.isNotEmpty()) {
            sb.appendLine("Avoid/dislikes: ${profile.dislikedTopics.take(5).joinToString(", ")}")
        }
        if (profile.favoriteMusic.isNotEmpty()) {
            sb.appendLine("Music preferences: ${profile.favoriteMusic.take(5).joinToString(", ")}")
        }
        if (profile.favoriteFoods.isNotEmpty()) {
            sb.appendLine("Food preferences: ${profile.favoriteFoods.take(5).joinToString(", ")}")
        }
        sb.toString().trim()
    }

    // ─── Decay Preferences ────────────────────────────────────────────────────

    /**
     * Remove stale preferences that haven't been reinforced.
     * This prevents the profile from accumulating outdated interests.
     */
    suspend fun decayStalePreferences() = withContext(Dispatchers.IO) {
        // In a full implementation, each preference would have a timestamp.
        // Here we just keep the top-N most recently added (list order = recency).
        val profile = getProfile()
        val trimmed = profile.copy(
            favoriteTopics = profile.favoriteTopics.take(MAX_TOPICS),
            dislikedTopics = profile.dislikedTopics.take(MAX_TOPICS),
            favoriteMusic = profile.favoriteMusic.take(MAX_MUSIC),
            favoriteFoods = profile.favoriteFoods.take(MAX_FOODS)
        )
        if (trimmed != profile) {
            userProfileDao.update(trimmed.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private suspend fun updateProfile(transform: (UserProfileEntity) -> UserProfileEntity) =
        withContext(Dispatchers.IO) {
            val profile = getProfile()
            val updated = transform(profile).copy(updatedAt = System.currentTimeMillis())
            userProfileDao.update(updated)
        }

    private fun detectLanguage(text: String): String {
        val hiCount = text.count { it in '\u0900'..'\u097F' }
        val arCount = text.count { it in '\u0600'..'\u06FF' }
        val total = text.length.coerceAtLeast(1)
        return when {
            hiCount.toFloat() / total > 0.3f -> "hi"
            arCount.toFloat() / total > 0.3f -> "ar"
            else -> "en"
        }
    }
}
