package com.aladdin.tools

import com.aladdin.tools.tools.ToolResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ToolResultTest {

    @Test fun `success result has correct fields`() {
        val result = ToolResult.success("calculator", "= 42", 150L)
        assertThat(result.toolId).isEqualTo("calculator")
        assertThat(result.success).isTrue()
        assertThat(result.output).isEqualTo("= 42")
        assertThat(result.error).isNull()
        assertThat(result.durationMs).isEqualTo(150L)
    }

    @Test fun `error result has correct fields`() {
        val result = ToolResult.error("calculator", "Division by zero")
        assertThat(result.toolId).isEqualTo("calculator")
        assertThat(result.success).isFalse()
        assertThat(result.error).isEqualTo("Division by zero")
        assertThat(result.output).isEmpty()
    }

    @Test fun `toString of success returns output`() {
        val result = ToolResult.success("tool", "Hello")
        assertThat(result.toString()).isEqualTo("Hello")
    }

    @Test fun `toString of error includes Error prefix`() {
        val result = ToolResult.error("tool", "Something went wrong")
        assertThat(result.toString()).contains("Error")
        assertThat(result.toString()).contains("Something went wrong")
    }

    @Test fun `success factory default duration is 0`() {
        val result = ToolResult.success("tool", "output")
        assertThat(result.durationMs).isEqualTo(0L)
    }

    @Test fun `data class equality`() {
        val r1 = ToolResult.success("tool", "output", 100L)
        val r2 = ToolResult.success("tool", "output", 100L)
        assertThat(r1).isEqualTo(r2)
    }

    @Test fun `data class inequality on different outputs`() {
        val r1 = ToolResult.success("tool", "output1")
        val r2 = ToolResult.success("tool", "output2")
        assertThat(r1).isNotEqualTo(r2)
    }
}
