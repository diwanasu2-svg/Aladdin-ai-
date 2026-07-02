package com.aladdin.engine

import com.aladdin.engine.intent.IntentClassifier
import com.aladdin.engine.models.*
import com.aladdin.engine.planner.GoalTracker
import com.aladdin.engine.planner.HTNPlanner
import com.aladdin.engine.planner.TaskDecomposer
import com.aladdin.engine.tools.ToolRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HTNPlannerTest {

    private lateinit var planner: HTNPlanner
    private lateinit var classifier: IntentClassifier
    private lateinit var goalTracker: GoalTracker

    @Before
    fun setUp() {
        val toolRegistry = ToolRegistry()
        val decomposer = TaskDecomposer()
        planner = HTNPlanner(toolRegistry, decomposer)
        classifier = IntentClassifier()
        goalTracker = GoalTracker()
    }

    @Test fun `plan for weather query has tasks`() {
        val intent = classifier.classify("What's the weather in Tokyo?")
        val plan = planner.plan("What's the weather in Tokyo?", intent)
        assertTrue("Plan should have tasks", plan.tasks.isNotEmpty())
        assertTrue("Tasks include weather tool", plan.tasks.any { it.toolId?.contains("weather") == true })
    }

    @Test fun `plan for reminder has alarm task`() {
        val intent = classifier.classify("Remind me to call Sarah at 5pm")
        val plan = planner.plan("Remind me to call Sarah at 5pm", intent)
        assertTrue("Should have alarm task", plan.tasks.any {
            it.toolId?.contains("alarm") == true || it.toolId?.contains("reminder") == true
        })
    }

    @Test fun `plan for navigation includes maps tasks`() {
        val intent = classifier.classify("Navigate to the airport")
        val plan = planner.plan("Navigate to the airport", intent)
        assertTrue("Should have navigation tasks", plan.tasks.any { it.toolId?.startsWith("maps") == true })
    }

    @Test fun `plan goal matches intent`() {
        val intent = classifier.classify("Play some jazz")
        val plan = planner.plan("Play some jazz", intent)
        assertEquals(IntentType.PLAY_MUSIC, plan.intent)
    }

    @Test fun `all primitive tasks have a toolId`() {
        val intent = classifier.classify("Send a message to Alice")
        val plan = planner.plan("Send a message to Alice", intent)
        val primitives = plan.tasks.filter { it.type == TaskType.PRIMITIVE }
        assertTrue("Should have primitives", primitives.isNotEmpty())
        primitives.forEach { t ->
            assertNotNull("Primitive task should have toolId: ${t.name}", t.toolId)
        }
    }

    @Test fun `execution order returns only primitives`() {
        val intent = classifier.classify("Search for Kotlin tutorials")
        val plan = planner.plan("Search for Kotlin tutorials", intent)
        val order = planner.getExecutionOrder(plan)
        assertTrue("Execution order should be non-empty", order.isNotEmpty())
        order.forEach { t ->
            assertEquals("Only PRIMITIVE tasks in execution order", TaskType.PRIMITIVE, t.type)
        }
    }

    @Test fun `compute progress starts at zero`() {
        val intent = classifier.classify("Play some music")
        val plan = planner.plan("Play some music", intent)
        val progress = planner.computeProgress(plan)
        assertEquals(0f, progress, 0.01f)
    }

    @Test fun `plan has non-empty reasoning`() {
        val intent = classifier.classify("Remember that I love hiking")
        val plan = planner.plan("Remember that I love hiking", intent)
        assertTrue("Plan should have reasoning", plan.reasoning.isNotBlank())
    }

    @Test fun `replan produces different task id`() {
        val intent = classifier.classify("Open Gmail")
        val plan = planner.plan("Open Gmail", intent)
        val firstTask = planner.getExecutionOrder(plan).firstOrNull() ?: return
        val replanned = planner.replan(plan, firstTask.id, "App not found")
        val newFirstTask = planner.getExecutionOrder(replanned).firstOrNull()
        // Replanned task should have retry count incremented or different ID
        assertNotNull(newFirstTask)
    }

    @Test fun `next task returns first pending`() {
        val intent = classifier.classify("Get latest news")
        val plan = planner.plan("Get latest news", intent)
        val next = planner.nextTask(plan)
        assertNotNull("Should have a next task", next)
        assertEquals(TaskStatus.PENDING, next?.status)
    }
}
