package com.aladdin.engine

import com.aladdin.engine.intent.IntentClassifier
import com.aladdin.engine.llm.LLMClient
import com.aladdin.engine.models.*
import com.aladdin.engine.reasoning.ReasoningEngine
import com.aladdin.engine.reasoning.SelfReflector
import com.aladdin.engine.planner.GoalTracker
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ReasoningEngineTest {

    private lateinit var engine: ReasoningEngine
    private lateinit var reflector: SelfReflector
    private lateinit var llmClient: LLMClient

    @Before
    fun setUp() {
        llmClient = mockk {
            coEvery { complete(any(), any()) } returns "This is a helpful response. [confidence: 0.85]"
        }
        engine = ReasoningEngine(llmClient)
        reflector = SelfReflector(llmClient)
    }

    @Test fun `reasoning chain has at least one step`() = runBlocking {
        val classifier = IntentClassifier()
        val intent = classifier.classify("What is the speed of light?")
        val chain = engine.reason("What is the speed of light?", intent = intent)
        assertTrue("Should have steps", chain.steps.isNotEmpty())
    }

    @Test fun `reasoning chain has final step`() = runBlocking {
        val chain = engine.reason("Tell me a joke")
        assertTrue("Should have final step", chain.steps.any { it.isFinal })
    }

    @Test fun `conclusion is not blank`() = runBlocking {
        val chain = engine.reason("What is 2+2?")
        assertTrue("Conclusion should not be blank", chain.conclusion.isNotBlank())
    }

    @Test fun `confidence is in range 0 to 1`() = runBlocking {
        val chain = engine.reason("What's the weather?")
        assertTrue("Confidence in range", chain.confidence in 0f..1f)
    }

    @Test fun `fast reason returns immediately without LLM`() {
        val classifier = IntentClassifier()
        val intent = classifier.classify("Hello")
        val chain = engine.reasonFast("Hello", intent)
        assertTrue("Fast chain has steps", chain.steps.isNotEmpty())
    }

    @Test fun `format chain produces readable output`() = runBlocking {
        val chain = engine.reason("Who is Nikola Tesla?")
        val formatted = engine.formatChain(chain)
        assertTrue("Formatted chain not empty", formatted.isNotBlank())
        assertTrue("Contains 'Thought'", formatted.contains("Thought"))
    }

    @Test fun `reflection on good response is acceptable`() = runBlocking {
        val plan = Plan(
            id = "test-plan",
            goal = "Answer the question",
            intent = IntentType.QUESTION_ANSWERING,
            tasks = listOf(
                Task(id = "t1", name = "Answer", type = TaskType.PRIMITIVE,
                    status = TaskStatus.COMPLETED, toolId = "llm.answer")
            ),
            status = PlanStatus.COMPLETED,
            progress = 1f
        )
        val reflection = reflector.reflectOnPlan(plan, "The speed of light is approximately 299,792,458 meters per second, which is an incredibly fast constant of the universe.", "What is the speed of light?")
        assertTrue("Good response should be acceptable", reflection.isAcceptable)
    }

    @Test fun `reflection on empty response is not acceptable`() = runBlocking {
        val plan = Plan("id", "goal", IntentType.UNKNOWN, emptyList())
        val reflection = reflector.reflectOnPlan(plan, "", "Tell me about AI")
        assertFalse("Empty response should not be acceptable", reflection.isAcceptable)
    }

    @Test fun `detect inconsistencies finds negation conflict`() {
        val issues = reflector.detectInconsistencies(
            response = "The user does not like coffee",
            memoryContext = "User loves coffee and drinks it every morning"
        )
        assertTrue("Should detect potential contradiction", issues.isNotEmpty())
    }

    @Test fun `self reflection response quality reflects word count`() = runBlocking {
        val short = reflector.reflectOnResponse("No.", "Explain quantum mechanics in detail")
        val long = reflector.reflectOnResponse(
            "Quantum mechanics is a fundamental theory in physics that describes the behavior of matter and energy at the smallest scales, including atoms, electrons, and photons. Unlike classical physics, it introduces concepts such as wave-particle duality, superposition, and quantum entanglement.",
            "Explain quantum mechanics in detail"
        )
        assertTrue("Longer, relevant response should score higher", long.qualityScore >= short.qualityScore)
    }
}
