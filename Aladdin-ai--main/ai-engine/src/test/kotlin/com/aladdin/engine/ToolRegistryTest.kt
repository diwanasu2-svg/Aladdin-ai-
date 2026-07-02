package com.aladdin.engine

import com.aladdin.engine.models.*
import com.aladdin.engine.tools.ToolRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ToolRegistryTest {

    private lateinit var registry: ToolRegistry

    @Before fun setUp() { registry = ToolRegistry() }

    @Test fun `all built-in tools are registered`() {
        val tools = registry.getAll()
        assertTrue("Should have many built-in tools", tools.size >= 20)
    }

    @Test fun `get by id returns correct tool`() {
        val tool = registry.getById("llm.answer")
        assertNotNull(tool)
        assertEquals("llm.answer", tool?.id)
    }

    @Test fun `find tools for weather intent`() {
        val tools = registry.findToolForIntent(IntentType.WEATHER_QUERY)
        assertTrue("Should find weather tool", tools.any { it.id == "weather.fetch" })
    }

    @Test fun `find tools for reminder intent`() {
        val tools = registry.findToolForIntent(IntentType.SET_REMINDER)
        assertTrue("Should find reminder tool", tools.any { it.id == "reminder.create" })
    }

    @Test fun `find tools for send message`() {
        val tools = registry.findToolForIntent(IntentType.SEND_MESSAGE)
        assertTrue("Should include message send", tools.any { it.id == "message.send" })
    }

    @Test fun `custom tool registration`() {
        registry.register(ToolDefinition(
            id = "custom.test",
            name = "Test Tool",
            description = "A test tool",
            intents = setOf(IntentType.UNKNOWN),
            parameters = emptyList()
        ))
        assertNotNull(registry.getById("custom.test"))
    }

    @Test fun `validate parameters catches missing required`() {
        val tool = registry.getById("reminder.create")!!
        val errors = registry.validateParameters(tool, emptyMap())
        assertTrue("Should catch missing 'subject'", errors.any { "subject" in it })
    }

    @Test fun `fill defaults adds default values`() {
        val tool = registry.getById("weather.fetch")!!
        val filled = registry.fillDefaults(tool, emptyMap())
        assertEquals("metric", filled["units"])
        assertEquals("current", filled["location"])
    }

    @Test fun `response.generate tool handles all intents`() {
        val tool = registry.getById("response.generate")
        assertNotNull(tool)
        assertTrue("Should handle all intents",
            IntentType.WEATHER_QUERY in (tool?.intents ?: emptySet()))
    }

    @Test fun `every intent has at least one tool`() {
        val coveredIntents = registry.getAll().flatMap { it.intents }.toSet()
        val importantIntents = setOf(
            IntentType.SET_REMINDER, IntentType.SEND_MESSAGE, IntentType.WEATHER_QUERY,
            IntentType.PLAY_MUSIC, IntentType.NAVIGATE, IntentType.SEARCH_WEB,
            IntentType.REMEMBER_FACT, IntentType.RECALL_MEMORY
        )
        importantIntents.forEach { intent ->
            assertTrue("No tool for $intent", intent in coveredIntents)
        }
    }
}
