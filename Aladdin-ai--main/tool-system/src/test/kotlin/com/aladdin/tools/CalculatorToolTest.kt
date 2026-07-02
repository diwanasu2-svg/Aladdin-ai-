package com.aladdin.tools

import com.aladdin.tools.tools.CalculatorTool
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.math.*

class CalculatorToolTest {

    private lateinit var calculator: CalculatorTool

    @Before
    fun setUp() { calculator = CalculatorTool() }

    // ── Basic arithmetic ──────────────────────────────────────────────────────

    @Test fun `addition`() { assertThat(calculator.evaluate("2 + 3")).isEqualTo(5.0) }
    @Test fun `subtraction`() { assertThat(calculator.evaluate("10 - 4")).isEqualTo(6.0) }
    @Test fun `multiplication`() { assertThat(calculator.evaluate("6 * 7")).isEqualTo(42.0) }
    @Test fun `division`() { assertThat(calculator.evaluate("15 / 3")).isEqualTo(5.0) }
    @Test fun `modulo`() { assertThat(calculator.evaluate("17 % 5")).isEqualTo(2.0) }
    @Test fun `power`() { assertThat(calculator.evaluate("2 ^ 10")).isEqualTo(1024.0) }
    @Test fun `negative number`() { assertThat(calculator.evaluate("-5 + 3")).isEqualTo(-2.0) }
    @Test fun `decimal arithmetic`() { assertThat(calculator.evaluate("1.5 + 2.5")).isWithin(1e-10).of(4.0) }

    // ── Operator precedence ───────────────────────────────────────────────────

    @Test fun `multiplication before addition`() { assertThat(calculator.evaluate("2 + 3 * 4")).isEqualTo(14.0) }
    @Test fun `parentheses override precedence`() { assertThat(calculator.evaluate("(2 + 3) * 4")).isEqualTo(20.0) }
    @Test fun `nested parentheses`() { assertThat(calculator.evaluate("((2 + 3) * (4 - 1))")).isEqualTo(15.0) }

    // ── Math functions ────────────────────────────────────────────────────────

    @Test fun `sqrt`() { assertThat(calculator.evaluate("sqrt(144)")).isWithin(1e-10).of(12.0) }
    @Test fun `cbrt`() { assertThat(calculator.evaluate("cbrt(27)")).isWithin(1e-10).of(3.0) }
    @Test fun `abs`() { assertThat(calculator.evaluate("abs(-42)")).isEqualTo(42.0) }
    @Test fun `ln`() { assertThat(calculator.evaluate("ln(e)")).isWithin(1e-10).of(1.0) }
    @Test fun `log`() { assertThat(calculator.evaluate("log(1000)")).isWithin(1e-10).of(3.0) }
    @Test fun `log2`() { assertThat(calculator.evaluate("log2(8)")).isWithin(1e-10).of(3.0) }
    @Test fun `sin pi over 2`() { assertThat(calculator.evaluate("sin(pi/2)")).isWithin(1e-10).of(1.0) }
    @Test fun `cos 0`() { assertThat(calculator.evaluate("cos(0)")).isWithin(1e-10).of(1.0) }
    @Test fun `tan pi over 4`() { assertThat(calculator.evaluate("tan(pi/4)")).isWithin(1e-10).of(1.0) }
    @Test fun `ceil`() { assertThat(calculator.evaluate("ceil(3.1)")).isEqualTo(4.0) }
    @Test fun `floor`() { assertThat(calculator.evaluate("floor(3.9)")).isEqualTo(3.0) }
    @Test fun `exp`() { assertThat(calculator.evaluate("exp(0)")).isWithin(1e-10).of(1.0) }

    // ── Constants ─────────────────────────────────────────────────────────────

    @Test fun `pi constant`() { assertThat(calculator.evaluate("pi")).isWithin(1e-10).of(Math.PI) }
    @Test fun `e constant`() { assertThat(calculator.evaluate("e")).isWithin(1e-10).of(Math.E) }
    @Test fun `phi constant`() { assertThat(calculator.evaluate("phi")).isWithin(1e-6).of(1.618033988749895) }

    // ── Factorial ─────────────────────────────────────────────────────────────

    @Test fun `factorial 0`() { assertThat(calculator.evaluate("0!")).isEqualTo(1.0) }
    @Test fun `factorial 5`() { assertThat(calculator.evaluate("5!")).isEqualTo(120.0) }
    @Test fun `factorial 10`() { assertThat(calculator.evaluate("10!")).isEqualTo(3628800.0) }
    @Test fun `factorial in expression`() { assertThat(calculator.evaluate("5! + 2^3")).isEqualTo(128.0) }

    // ── Unicode operators ─────────────────────────────────────────────────────

    @Test fun `multiply unicode`() { assertThat(calculator.evaluate("6 × 7")).isEqualTo(42.0) }
    @Test fun `divide unicode`() { assertThat(calculator.evaluate("15 ÷ 3")).isEqualTo(5.0) }
    @Test fun `pi unicode`() { assertThat(calculator.evaluate("π")).isWithin(1e-10).of(Math.PI) }

    // ── Error cases ───────────────────────────────────────────────────────────

    @Test fun `division by zero throws`() {
        try { calculator.evaluate("5 / 0"); assert(false) { "Expected exception" } }
        catch (e: ArithmeticException) { assertThat(e.message).contains("zero") }
    }

    @Test fun `unknown symbol throws`() {
        try { calculator.evaluate("foobar(5)"); assert(false) }
        catch (e: IllegalArgumentException) { assertThat(e.message).contains("Unknown") }
    }

    @Test fun `factorial out of range throws`() {
        try { calculator.evaluate("25!"); assert(false) }
        catch (e: IllegalArgumentException) { /* expected */ }
    }

    // ── execute() via params ──────────────────────────────────────────────────

    @Test fun `execute with expression param`() = runTest {
        val result = calculator.execute(mapOf("expression" to "2 + 2"))
        assertThat(result.success).isTrue()
        assertThat(result.output).contains("4")
    }

    @Test fun `execute with query param fallback`() = runTest {
        val result = calculator.execute(mapOf("query" to "sqrt(9)"))
        assertThat(result.success).isTrue()
        assertThat(result.output).contains("3")
    }

    @Test fun `execute missing param returns error`() = runTest {
        val result = calculator.execute(emptyMap())
        assertThat(result.success).isFalse()
        assertThat(result.error).isNotNull()
    }

    @Test fun `execute with complex expression`() = runTest {
        val result = calculator.execute(mapOf("expression" to "sqrt(144) + 2^3"))
        assertThat(result.success).isTrue()
        assertThat(result.output).contains("20")
    }

    // ── Tool identity ─────────────────────────────────────────────────────────

    @Test fun `tool id and name`() {
        assertThat(calculator.id).isEqualTo("calculator")
        assertThat(calculator.name).isNotEmpty()
        assertThat(calculator.description).isNotEmpty()
    }

    // ── Complex expressions ───────────────────────────────────────────────────

    @Test fun `complex multi-operation expression`() {
        val result = calculator.evaluate("sin(pi/6) * 2 + cos(pi/3)")
        assertThat(result).isWithin(1e-10).of(1.5)
    }

    @Test fun `right-associative power`() {
        assertThat(calculator.evaluate("2^3^2")).isWithin(1e-10).of(512.0)
    }

    @Test fun `large number calculation`() {
        val result = calculator.evaluate("1000000 * 1000000")
        assertThat(result).isEqualTo(1_000_000_000_000.0)
    }
}
