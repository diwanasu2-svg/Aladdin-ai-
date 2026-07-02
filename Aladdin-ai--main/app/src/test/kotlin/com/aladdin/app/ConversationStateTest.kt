package com.aladdin.app

import com.aladdin.app.conversation.ConversationState
import com.aladdin.app.conversation.ConversationTurn
import com.aladdin.app.conversation.Role
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConversationStateTest {

    // ConversationState is a sealed class with Idle, Listening, Thinking,
    // Speaking(text), and Error(message) subtypes.

    @Test fun `Idle state is distinct`() {
        val state: ConversationState = ConversationState.Idle
        assertThat(state).isInstanceOf(ConversationState.Idle::class.java)
    }

    @Test fun `Listening state is distinct`() {
        val state: ConversationState = ConversationState.Listening
        assertThat(state).isInstanceOf(ConversationState.Listening::class.java)
    }

    @Test fun `Thinking state is distinct`() {
        val state: ConversationState = ConversationState.Thinking
        assertThat(state).isInstanceOf(ConversationState.Thinking::class.java)
    }

    @Test fun `Speaking state carries text`() {
        val state = ConversationState.Speaking("Hello world")
        assertThat(state).isInstanceOf(ConversationState.Speaking::class.java)
        assertThat((state as ConversationState.Speaking).text).isEqualTo("Hello world")
    }

    @Test fun `Error state carries message`() {
        val state = ConversationState.Error("Microphone unavailable")
        assertThat(state).isInstanceOf(ConversationState.Error::class.java)
        assertThat((state as ConversationState.Error).message).isEqualTo("Microphone unavailable")
    }

    @Test fun `states are not equal across types`() {
        assertThat(ConversationState.Idle).isNotEqualTo(ConversationState.Listening)
        assertThat(ConversationState.Thinking).isNotEqualTo(ConversationState.Idle)
    }

    @Test fun `Speaking equality based on text`() {
        val s1 = ConversationState.Speaking("Hello")
        val s2 = ConversationState.Speaking("Hello")
        val s3 = ConversationState.Speaking("World")
        assertThat(s1).isEqualTo(s2)
        assertThat(s1).isNotEqualTo(s3)
    }

    @Test fun `when expression covers all states`() {
        val states: List<ConversationState> = listOf(
            ConversationState.Idle,
            ConversationState.Listening,
            ConversationState.Thinking,
            ConversationState.Speaking("test"),
            ConversationState.Error("error")
        )
        val labels = states.map { state ->
            when (state) {
                is ConversationState.Idle      -> "idle"
                is ConversationState.Listening -> "listening"
                is ConversationState.Thinking  -> "thinking"
                is ConversationState.Speaking  -> "speaking:${state.text}"
                is ConversationState.Error     -> "error:${state.message}"
            }
        }
        assertThat(labels).containsExactly("idle", "listening", "thinking", "speaking:test", "error:error").inOrder()
    }

    @Test fun `ConversationTurn holds role and text`() {
        val turn = ConversationTurn(Role.USER, "What is the weather?")
        assertThat(turn.role).isEqualTo(Role.USER)
        assertThat(turn.text).isEqualTo("What is the weather?")
        assertThat(turn.timestampMs).isGreaterThan(0L)
    }

    @Test fun `Role labels match Gemini API format`() {
        assertThat(Role.USER.label).isEqualTo("user")
        assertThat(Role.ASSISTANT.label).isEqualTo("model")
        assertThat(Role.SYSTEM.label).isEqualTo("system")
    }

    @Test fun `Role has 3 distinct values`() {
        assertThat(Role.values()).hasLength(3)
        assertThat(Role.values().map { it.label }.distinct()).hasSize(3)
    }

    @Test fun `conversation turn CRUD test - 50 turns`() {
        val turns = (1..50).map { i ->
            ConversationTurn(
                role = if (i % 2 == 0) Role.ASSISTANT else Role.USER,
                text = "Message number $i"
            )
        }
        assertThat(turns).hasSize(50)
        assertThat(turns.filter { it.role == Role.USER }).hasSize(25)
        assertThat(turns.filter { it.role == Role.ASSISTANT }).hasSize(25)
    }
}
