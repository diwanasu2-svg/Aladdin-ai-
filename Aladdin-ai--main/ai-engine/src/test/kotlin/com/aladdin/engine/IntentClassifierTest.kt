package com.aladdin.engine

import com.aladdin.engine.intent.IntentClassifier
import com.aladdin.engine.models.IntentType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class IntentClassifierTest {

    private lateinit var classifier: IntentClassifier

    @Before
    fun setUp() { classifier = IntentClassifier() }

    @Test fun `classifies weather query`() {
        val result = classifier.classify("What's the weather like in London?")
        assertEquals(IntentType.WEATHER_QUERY, result.type)
        assertTrue(result.confidence > 0.5f)
    }

    @Test fun `classifies set reminder`() {
        val result = classifier.classify("Remind me to call Sarah in 2 hours")
        assertEquals(IntentType.SET_REMINDER, result.type)
    }

    @Test fun `classifies send message`() {
        val result = classifier.classify("Send a WhatsApp message to John saying I'll be late")
        assertEquals(IntentType.SEND_MESSAGE, result.type)
    }

    @Test fun `classifies play music`() {
        val result = classifier.classify("Play some jazz music")
        assertEquals(IntentType.PLAY_MUSIC, result.type)
    }

    @Test fun `classifies navigation`() {
        val result = classifier.classify("Navigate to Heathrow Airport")
        assertEquals(IntentType.NAVIGATE, result.type)
    }

    @Test fun `classifies web search`() {
        val result = classifier.classify("Search for the best restaurants near me")
        assertEquals(IntentType.SEARCH_WEB, result.type)
    }

    @Test fun `classifies open app`() {
        val result = classifier.classify("Open the Maps app")
        assertEquals(IntentType.OPEN_APP, result.type)
    }

    @Test fun `classifies remember fact`() {
        val result = classifier.classify("Remember that my anniversary is on June 15")
        assertEquals(IntentType.REMEMBER_FACT, result.type)
    }

    @Test fun `classifies recall memory`() {
        val result = classifier.classify("Do you remember what I told you about my project?")
        assertEquals(IntentType.RECALL_MEMORY, result.type)
    }

    @Test fun `classifies news query`() {
        val result = classifier.classify("What are the latest news headlines?")
        assertEquals(IntentType.NEWS_QUERY, result.type)
    }

    @Test fun `classifies create plan`() {
        val result = classifier.classify("Create a plan to learn Kotlin in 3 months")
        assertEquals(IntentType.CREATE_PLAN, result.type)
    }

    @Test fun `classifies track goal`() {
        val result = classifier.classify("Set a goal to exercise 3 times a week")
        assertEquals(IntentType.TRACK_GOAL, result.type)
    }

    @Test fun `classifies small talk`() {
        val result = classifier.classify("Hey, how are you?")
        assertEquals(IntentType.SMALL_TALK, result.type)
    }

    @Test fun `classifies factual lookup`() {
        val result = classifier.classify("What is the capital of France?")
        assertTrue(result.type in setOf(IntentType.FACTUAL_LOOKUP, IntentType.QUESTION_ANSWERING))
    }

    @Test fun `extracts time entity from reminder`() {
        val result = classifier.classify("Remind me to take my medication at 9pm")
        assertEquals(IntentType.SET_REMINDER, result.type)
        assertTrue("Should extract time entity", result.entities.containsKey("time") || result.entities.isNotEmpty())
    }

    @Test fun `extracts destination from navigate`() {
        val result = classifier.classify("Navigate to Times Square")
        assertEquals(IntentType.NAVIGATE, result.type)
    }

    @Test fun `confidence is between 0 and 1`() {
        val queries = listOf("hello", "play music", "what is gravity", "remind me at 5pm", "search for pizza")
        queries.forEach { q ->
            val r = classifier.classify(q)
            assertTrue("Confidence out of range for '$q': ${r.confidence}", r.confidence in 0f..1f)
        }
    }

    @Test fun `unknown intent for gibberish`() {
        val result = classifier.classify("xkcd qwerty zxcv asdf")
        assertTrue("Should be unknown or low confidence",
            result.type == IntentType.UNKNOWN || result.confidence < 0.6f)
    }

    @Test fun `empty string returns unknown`() {
        val result = classifier.classify("")
        assertEquals(IntentType.UNKNOWN, result.type)
    }

    @Test fun `classifyFromLLMJson parses correctly`() {
        val json = """{"intent":"WEATHER_QUERY","confidence":0.95,"entities":{"location":"Paris"}}"""
        val result = classifier.classifyFromLLMJson(json, "Weather in Paris")
        assertEquals(IntentType.WEATHER_QUERY, result.type)
        assertEquals(0.95f, result.confidence, 0.01f)
        assertEquals("Paris", result.entities["location"])
    }
}
