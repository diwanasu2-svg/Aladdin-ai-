package com.aladdin.tools

import com.aladdin.tools.tools.CalculatorTool
import com.aladdin.tools.tools.ToolResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AdditionalToolTest {

    @Test fun `tool interface contract - id is non-empty`() = runTest {
        val calc = CalculatorTool()
        assertThat(calc.id).isNotEmpty()
        assertThat(calc.name).isNotEmpty()
        assertThat(calc.description).isNotEmpty()
    }

    @Test fun `calculator handles all arithmetic operators`() = runTest {
        val calc = CalculatorTool()
        assertThat(calc.evaluate("100 + 50")).isEqualTo(150.0)
        assertThat(calc.evaluate("100 - 50")).isEqualTo(50.0)
        assertThat(calc.evaluate("100 * 50")).isEqualTo(5000.0)
        assertThat(calc.evaluate("100 / 50")).isEqualTo(2.0)
        assertThat(calc.evaluate("100 % 30")).isEqualTo(10.0)
        assertThat(calc.evaluate("2 ^ 8")).isEqualTo(256.0)
    }

    @Test fun `error handling - execute with null-like params`() = runTest {
        val calc = CalculatorTool()
        val result = calc.execute(mapOf("invalid_key" to "2+2"))
        assertThat(result.success).isFalse()
        assertThat(result.error).isNotNull()
    }

    @Test fun `ToolResult success factory`() {
        val r = ToolResult.success("calc", "42")
        assertThat(r.success).isTrue()
        assertThat(r.output).isEqualTo("42")
        assertThat(r.error).isNull()
    }

    @Test fun `ToolResult error factory`() {
        val r = ToolResult.error("calc", "division by zero")
        assertThat(r.success).isFalse()
        assertThat(r.error).isEqualTo("division by zero")
    }

    @Test fun `integration - chained calculations`() = runTest {
        val calc = CalculatorTool()
        val r1 = calc.execute(mapOf("expression" to "10 * 5"))
        assertThat(r1.success).isTrue()
        // Use result in next calculation
        val r2 = calc.execute(mapOf("expression" to "50 + 25"))
        assertThat(r2.success).isTrue()
        assertThat(r2.output).contains("75")
    }
}
