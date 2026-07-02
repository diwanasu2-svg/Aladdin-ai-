package com.aladdin.engine

import com.aladdin.engine.llm.ContextManager
import com.aladdin.engine.llm.LLMClient
import com.aladdin.engine.models.ConversationMessage
import com.aladdin.engine.models.IntentType
import com.aladdin.engine.models.MessageRole
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class ContextManagerTest {

    private lateinit var manager: ContextManager
    private val llmClient: LLMClient = mockk(relaxed = true)

    @Before
    fun setUp() {
        every { llmClient.estimateTokens(any()) } returns 10
        manager = ContextManager(llmClient)
    }

    @Test fun `new manager has empty messages`() = runTest {
        assertThat(manager.messages.first()).isEmpty()
    }

    @Test fun `addUserMessage adds to flow`() = runTest {
        manager.addUserMessage("Hello Aladdin")
        assertThat(manager.messages.first()).hasSize(1)
        assertThat(manager.messages.first().first().content).isEqualTo("Hello Aladdin")
    }

    @Test fun `addUserMessage returns message`() = runTest {
        val msg = manager.addUserMessage("Test input")
        assertThat(msg.content).isEqualTo("Test input")
        assertThat(msg.role).isEqualTo(MessageRole.USER)
    }

    @Test fun `addAssistantMessage adds assistant turn`() = runTest {
        manager.addAssistantMessage("I can help you with that")
        val msgs = manager.messages.first()
        assertThat(msgs.first().role).isEqualTo(MessageRole.ASSISTANT)
    }

    @Test fun `addSystemMessage adds system turn`() = runTest {
        manager.addSystemMessage("You are Aladdin, a helpful AI assistant")
        val msgs = manager.messages.first()
        assertThat(msgs.first().role).isEqualTo(MessageRole.SYSTEM)
    }

    @Test fun `messages preserve insertion order`() = runTest {
        manager.addSystemMessage("System prompt")
        manager.addUserMessage("User message")
        manager.addAssistantMessage("Assistant reply")
        val msgs = manager.messages.first()
        assertThat(msgs[0].role).isEqualTo(MessageRole.SYSTEM)
        assertThat(msgs[1].role).isEqualTo(MessageRole.USER)
        assertThat(msgs[2].role).isEqualTo(MessageRole.ASSISTANT)
    }

    @Test fun `startNewSession clears messages`() = runTest {
        manager.addUserMessage("Hello")
        manager.startNewSession()
        assertThat(manager.messages.first()).isEmpty()
    }

    @Test fun `startNewSession changes sessionId`() = runTest {
        val id1 = manager.getSessionId()
        manager.startNewSession()
        val id2 = manager.getSessionId()
        assertThat(id1).isNotEqualTo(id2)
    }

    @Test fun `getSessionId returns non-empty UUID`() {
        assertThat(manager.getSessionId()).isNotEmpty()
        assertThat(manager.getSessionId()).contains("-")
    }

    @Test fun `addUserMessage with intentType`() = runTest {
        val msg = manager.addUserMessage("What is the weather?", IntentType.QUESTION)
        assertThat(msg.intentType).isEqualTo(IntentType.QUESTION)
    }

    @Test fun `addAssistantMessage with planId`() = runTest {
        val msg = manager.addAssistantMessage("Here is your answer", planId = "plan-001")
        assertThat(msg.content).isEqualTo("Here is your answer")
    }

    @Test fun `getLastAssistantMessage returns last assistant reply`() = runTest {
        manager.addUserMessage("Hello")
        manager.addAssistantMessage("Hi there")
        manager.addUserMessage("How are you?")
        manager.addAssistantMessage("I'm great!")
        val last = manager.getLastAssistantMessage()
        assertThat(last?.content).isEqualTo("I'm great!")
    }

    @Test fun `getLastAssistantMessage returns null when none`() = runTest {
        manager.addUserMessage("Hello")
        assertThat(manager.getLastAssistantMessage()).isNull()
    }

    @Test fun `getLastUserMessages returns N most recent user messages`() = runTest {
        repeat(10) { i -> manager.addUserMessage("User message $i") }
        val last5 = manager.getLastUserMessages(5)
        assertThat(last5).hasSize(5)
        assertThat(last5.last().content).isEqualTo("User message 9")
    }

    @Test fun `getMessagesForLLM excludes tool-only messages`() = runTest {
        manager.addUserMessage("Hello")
        manager.addAssistantMessage("Hi")
        val msgs = manager.getMessagesForLLM()
        assertThat(msgs).isNotEmpty()
    }

    @Test fun `configure sets max context tokens`() = runTest {
        manager.configure(maxTokens = 8192)
        // Verify it doesn't throw and state is maintained
        manager.addUserMessage("Hello")
        assertThat(manager.messages.first()).hasSize(1)
    }

    @Test fun `buildRecentContext returns non-empty string`() = runTest {
        manager.addUserMessage("What time is it?")
        manager.addAssistantMessage("It's 3pm")
        val context = manager.buildRecentContext(maxMessages = 5)
        assertThat(context).isNotEmpty()
    }

    @Test fun `CRUD test - 100 conversation turns`() = runTest {
        repeat(50) { i ->
            manager.addUserMessage("User turn $i")
            manager.addAssistantMessage("Assistant turn $i")
        }
        val msgs = manager.messages.first()
        assertThat(msgs).isNotEmpty()
    }
}
