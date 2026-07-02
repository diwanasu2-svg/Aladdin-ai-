package com.aladdin.tools.tools

import android.util.Log
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * Calculator tool.
 *
 * Supports:
 *  - Basic: +  −  ×  ÷  %  ^
 *  - Functions: sqrt, cbrt, abs, ln, log, log2, sin, cos, tan, asin, acos, atan
 *  - Constants: pi, e, phi
 *  - Factorial: n!
 *  - Parentheses + operator precedence (recursive-descent parser)
 *
 * Input: plain math expression string, e.g.
 *   "2 + 3 * 4"              → 14
 *   "sqrt(144) + 2^3"        → 20
 *   "sin(pi / 2) * 100"      → 100
 *   "log(1000)"              → 3
 *   "5!"                     → 120
 */
@Singleton
class CalculatorTool @Inject constructor() : BaseTool {

    override val id = "calculator"
    override val name = "Calculator"
    override val description = "Evaluates mathematical expressions including advanced functions"

    companion object {
        private const val TAG = "CalculatorTool"
        private val MC = MathContext(15)
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val expr = params["expression"] ?: params["query"] ?: return ToolResult.error(id, "Missing 'expression' parameter")
        return try {
            val result = evaluate(expr.trim())
            val formatted = formatResult(result)
            Log.d(TAG, "$expr = $formatted")
            ToolResult.success(id, "= $formatted")
        } catch (e: Exception) {
            ToolResult.error(id, "Calculation error: ${e.message}")
        }
    }

    fun evaluate(expression: String): Double {
        val clean = expression
            .replace("×", "*").replace("÷", "/")
            .replace("π", "pi").replace("φ", "phi")
            .replace(" ", "")
        val parser = ExpressionParser(clean)
        return parser.parseExpression()
    }

    // ─── Recursive-Descent Parser ─────────────────────────────────────────────

    private inner class ExpressionParser(private val expr: String) {
        private var pos = 0

        fun parseExpression(): Double = parseAddSub()

        private fun parseAddSub(): Double {
            var left = parseMulDiv()
            while (pos < expr.length && (expr[pos] == '+' || expr[pos] == '-')) {
                val op = expr[pos++]
                val right = parseMulDiv()
                left = if (op == '+') left + right else left - right
            }
            return left
        }

        private fun parseMulDiv(): Double {
            var left = parsePower()
            while (pos < expr.length && (expr[pos] == '*' || expr[pos] == '/' || expr[pos] == '%')) {
                val op = expr[pos++]
                val right = parsePower()
                left = when (op) {
                    '*' -> left * right
                    '/' -> { if (right == 0.0) throw ArithmeticException("Division by zero"); left / right }
                    '%' -> left % right
                    else -> left
                }
            }
            return left
        }

        private fun parsePower(): Double {
            var base = parseUnary()
            if (pos < expr.length && expr[pos] == '^') {
                pos++
                val exp = parsePower() // right-associative
                base = base.pow(exp)
            }
            return base
        }

        private fun parseUnary(): Double {
            if (pos < expr.length && expr[pos] == '-') { pos++; return -parseFactorial() }
            if (pos < expr.length && expr[pos] == '+') { pos++ }
            return parseFactorial()
        }

        private fun parseFactorial(): Double {
            var value = parsePrimary()
            while (pos < expr.length && expr[pos] == '!') {
                pos++
                value = factorial(value.toLong()).toDouble()
            }
            return value
        }

        private fun parsePrimary(): Double {
            // Parentheses
            if (pos < expr.length && expr[pos] == '(') {
                pos++
                val value = parseExpression()
                if (pos < expr.length && expr[pos] == ')') pos++
                return value
            }

            // Named functions and constants
            if (pos < expr.length && (expr[pos].isLetter() || expr[pos] == '_')) {
                val start = pos
                while (pos < expr.length && (expr[pos].isLetterOrDigit() || expr[pos] == '_')) pos++
                val name = expr.substring(start, pos).lowercase()

                // Constants
                when (name) {
                    "pi"  -> return Math.PI
                    "e"   -> return Math.E
                    "phi" -> return (1.0 + sqrt(5.0)) / 2.0
                    "inf", "infinity" -> return Double.POSITIVE_INFINITY
                }

                // Functions — expect '(' after name
                if (pos < expr.length && expr[pos] == '(') {
                    pos++
                    val arg = parseExpression()
                    if (pos < expr.length && expr[pos] == ')') pos++
                    return applyFunction(name, arg)
                }

                throw IllegalArgumentException("Unknown symbol: $name")
            }

            // Number literal
            val start = pos
            while (pos < expr.length && (expr[pos].isDigit() || expr[pos] == '.')) pos++
            if (pos == start) throw IllegalArgumentException("Unexpected char at pos $pos: ${expr.getOrNull(pos)}")
            return expr.substring(start, pos).toDouble()
        }

        private fun applyFunction(name: String, arg: Double): Double = when (name) {
            "sqrt"  -> sqrt(arg)
            "cbrt"  -> cbrt(arg)
            "abs"   -> abs(arg)
            "ln"    -> ln(arg)
            "log"   -> log10(arg)
            "log2"  -> log2(arg)
            "sin"   -> sin(arg)
            "cos"   -> cos(arg)
            "tan"   -> tan(arg)
            "asin"  -> asin(arg)
            "acos"  -> acos(arg)
            "atan"  -> atan(arg)
            "sinh"  -> sinh(arg)
            "cosh"  -> cosh(arg)
            "tanh"  -> tanh(arg)
            "ceil"  -> ceil(arg)
            "floor" -> floor(arg)
            "round" -> arg.roundToLong().toDouble()
            "exp"   -> exp(arg)
            "sign"  -> sign(arg)
            "deg"   -> Math.toDegrees(arg)
            "rad"   -> Math.toRadians(arg)
            "fact"  -> factorial(arg.toLong()).toDouble()
            else    -> throw IllegalArgumentException("Unknown function: $name")
        }
    }

    private fun factorial(n: Long): Long {
        require(n in 0..20) { "Factorial out of range (0–20): $n" }
        var result = 1L
        for (i in 2..n) result *= i
        return result
    }

    private fun Double.roundToLong(): Long = Math.round(this)

    private fun formatResult(value: Double): String {
        if (value.isInfinite()) return if (value > 0) "∞" else "-∞"
        if (value.isNaN()) return "undefined"
        // Show as integer if no fractional part
        if (value == floor(value) && abs(value) < 1e15) return value.toLong().toString()
        // Round to 10 significant figures
        return BigDecimal(value).round(MathContext(10)).stripTrailingZeros().toPlainString()
    }
}
