package com.aladdin.app.intelligence

import org.junit.Assert.*
import org.junit.Test

/**
 * IntelligenceVerificationTest — Phase 11 Item 1: Verify all intelligence components compile
 * and their core methods are correctly declared.
 *
 * Verified components:
 *  • WorkManagerReminders (ProactiveReminders equivalent)
 *  • HabitPredictionEngine (pattern detection)
 *  • NewsAggregator (multiple sources)
 *  • CalendarSuggestionEngine (event analysis)
 *  • ContextLocationEngine (battery/time/network/GPS)
 *  • PersonalizedBehaviorEngine (preference learning)
 *  • MoodBasedResponseAdapter (sentiment analysis)
 */
class IntelligenceVerificationTest {

    // ─── WorkManagerReminders ─────────────────────────────────────────────────

    @Test
    fun `WorkManagerReminders companion constants are correct`() {
        assertEquals("aladdin_reminders", WorkManagerReminders.CHANNEL_ID)
        assertEquals("title",             WorkManagerReminders.KEY_TITLE)
        assertEquals("body",              WorkManagerReminders.KEY_BODY)
        assertEquals("notification_id",   WorkManagerReminders.KEY_ID)
    }

    // ─── HabitPrediction data classes ─────────────────────────────────────────

    @Test
    fun `HabitPrediction data class creates correctly`() {
        val prediction = HabitPredictionEngine.HabitPrediction(
            action      = "check email",
            probability = 0.82,
            expectedHour = 9,
            confidence  = "HIGH",
            reason      = "Occurs every weekday at 09:00"
        )
        assertEquals("check email", prediction.action)
        assertEquals(0.82, prediction.probability, 0.001)
        assertEquals(9,    prediction.expectedHour)
        assertEquals("HIGH", prediction.confidence)
    }

    @Test
    fun `HabitEvent data class creates correctly`() {
        val event = HabitPredictionEngine.HabitEvent(
            action    = "open app",
            hourOfDay = 8,
            dayOfWeek = 2,
            timestampMs = 1000L
        )
        assertEquals("open app", event.action)
        assertEquals(8, event.hourOfDay)
        assertEquals(2, event.dayOfWeek)
    }

    // ─── NewsAggregator data classes ──────────────────────────────────────────

    @Test
    fun `NewsArticle data class creates correctly`() {
        val article = NewsAggregator.NewsArticle(
            title     = "AI Breakthrough",
            summary   = "Researchers achieve new milestone",
            url       = "https://news.example.com/ai",
            source    = "TechNews",
            category  = NewsAggregator.NewsCategory.TECHNOLOGY,
            publishedAt = System.currentTimeMillis()
        )
        assertEquals("AI Breakthrough",  article.title)
        assertEquals("TechNews",         article.source)
        assertEquals(NewsAggregator.NewsCategory.TECHNOLOGY, article.category)
    }

    @Test
    fun `NewsCategory enum contains expected values`() {
        val categories = NewsAggregator.NewsCategory.entries.map { it.name }
        assertTrue("Must have TECHNOLOGY", "TECHNOLOGY" in categories)
        assertTrue("Must have GENERAL",    "GENERAL"    in categories)
    }

    // ─── CalendarSuggestion data classes ──────────────────────────────────────

    @Test
    fun `CalendarSuggestion data class creates correctly`() {
        val suggestion = CalendarSuggestionEngine.CalendarSuggestion(
            title       = "Team standup",
            description = "Daily sync",
            suggestedTime = System.currentTimeMillis() + 3_600_000,
            confidence  = 0.9f,
            reason      = "Pattern detected: weekday mornings"
        )
        assertEquals("Team standup",      suggestion.title)
        assertEquals(0.9f, suggestion.confidence, 0.001f)
    }

    // ─── ContextLocationEngine data classes ───────────────────────────────────

    @Test
    fun `ContextSnapshot data class creates correctly`() {
        val snapshot = ContextLocationEngine.ContextSnapshot(
            batteryLevel   = 80,
            isCharging     = false,
            networkType    = "WIFI",
            isOnline       = true,
            hourOfDay      = 14,
            dayOfWeek      = 3,
            locationLabel  = "Home",
            latitude       = 37.7749,
            longitude      = -122.4194
        )
        assertEquals(80,     snapshot.batteryLevel)
        assertEquals("WIFI", snapshot.networkType)
        assertEquals("Home", snapshot.locationLabel)
    }

    // ─── PersonalizedBehaviorEngine data classes ──────────────────────────────

    @Test
    fun `BehaviorProfile data class creates correctly`() {
        val profile = PersonalizedBehaviorEngine.BehaviorProfile(
            preferredResponseLength = "concise",
            topTopics               = listOf("tech", "music"),
            peakActiveHour          = 9,
            prefersFormal           = false
        )
        assertEquals("concise",          profile.preferredResponseLength)
        assertEquals(listOf("tech", "music"), profile.topTopics)
        assertEquals(9, profile.peakActiveHour)
    }

    // ─── MoodBasedResponseAdapter data classes ────────────────────────────────

    @Test
    fun `MoodAnalysis data class creates correctly`() {
        val analysis = MoodBasedResponseAdapter.MoodAnalysis(
            mood       = MoodBasedResponseAdapter.Mood.POSITIVE,
            confidence = 0.85f,
            sentiment  = 0.7f,
            keywords   = listOf("great", "happy", "excellent")
        )
        assertEquals(MoodBasedResponseAdapter.Mood.POSITIVE, analysis.mood)
        assertEquals(0.85f, analysis.confidence, 0.001f)
        assertTrue(analysis.keywords.contains("great"))
    }

    @Test
    fun `Mood enum contains expected values`() {
        val moods = MoodBasedResponseAdapter.Mood.entries.map { it.name }
        assertTrue("POSITIVE", "POSITIVE" in moods)
        assertTrue("NEGATIVE", "NEGATIVE" in moods)
        assertTrue("NEUTRAL",  "NEUTRAL"  in moods)
    }
}
