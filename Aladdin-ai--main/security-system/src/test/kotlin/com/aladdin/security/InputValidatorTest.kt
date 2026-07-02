package com.aladdin.security

import com.aladdin.security.validation.InputValidator
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class InputValidatorTest {

    private lateinit var validator: InputValidator

    @Before
    fun setUp() { validator = InputValidator() }

    // ── TEXT validation ───────────────────────────────────────────────────────

    @Test fun `valid plain text passes`() {
        val result = validator.validateText("Hello, how are you today?")
        assertThat(result.valid).isTrue()
        assertThat(result.violations).isEmpty()
    }

    @Test fun `XSS script tag is rejected`() {
        val result = validator.validateText("<script>alert('xss')</script>")
        assertThat(result.valid).isFalse()
        assertThat(result.violations).isNotEmpty()
    }

    @Test fun `SQL injection in text is rejected`() {
        val result = validator.validateText("'; DROP TABLE users; --")
        assertThat(result.valid).isFalse()
    }

    @Test fun `text exceeding max length is rejected`() {
        val longText = "a".repeat(5000)
        val result = validator.validateText(longText, maxLength = 100)
        assertThat(result.valid).isFalse()
        assertThat(result.violations.any { it.contains("max length") }).isTrue()
    }

    // ── EMAIL validation ──────────────────────────────────────────────────────

    @Test fun `valid email passes`() {
        val result = validator.validate("user@example.com", InputValidator.InputType.EMAIL)
        assertThat(result.valid).isTrue()
    }

    @Test fun `valid email with plus sign`() {
        val result = validator.validate("user+tag@example.co.uk", InputValidator.InputType.EMAIL)
        assertThat(result.valid).isTrue()
    }

    @Test fun `missing at sign fails email validation`() {
        val result = validator.validate("notanemail.com", InputValidator.InputType.EMAIL)
        assertThat(result.valid).isFalse()
    }

    @Test fun `missing domain fails email validation`() {
        val result = validator.validate("user@", InputValidator.InputType.EMAIL)
        assertThat(result.valid).isFalse()
    }

    @Test fun `email sanitized to lowercase`() {
        val result = validator.validate("User@EXAMPLE.COM", InputValidator.InputType.EMAIL)
        assertThat(result.sanitized).isEqualTo("user@example.com")
    }

    // ── URL validation ────────────────────────────────────────────────────────

    @Test fun `valid https url passes`() {
        val result = validator.validate("https://www.example.com/path?q=1", InputValidator.InputType.URL)
        assertThat(result.valid).isTrue()
    }

    @Test fun `valid http url passes`() {
        val result = validator.validate("http://example.com", InputValidator.InputType.URL)
        assertThat(result.valid).isTrue()
    }

    @Test fun `javascript protocol fails`() {
        val result = validator.validate("javascript:alert(1)", InputValidator.InputType.URL)
        assertThat(result.valid).isFalse()
    }

    // ── PATH validation ───────────────────────────────────────────────────────

    @Test fun `safe path passes`() {
        val result = validator.validatePath("/data/user/files/document.txt")
        assertThat(result.valid).isTrue()
    }

    @Test fun `path traversal attempt rejected`() {
        val result = validator.validatePath("/data/../../../etc/passwd")
        assertThat(result.valid).isFalse()
        assertThat(result.violations.any { it.contains("traversal") }).isTrue()
    }

    @Test fun `null byte in path rejected`() {
        val result = validator.validatePath("/data/file\u0000.txt")
        assertThat(result.valid).isFalse()
    }

    // ── FILENAME validation ───────────────────────────────────────────────────

    @Test fun `valid filename passes`() {
        val result = validator.validate("document.pdf", InputValidator.InputType.FILENAME)
        assertThat(result.valid).isTrue()
    }

    @Test fun `filename with slash fails`() {
        val result = validator.validate("subdir/file.txt", InputValidator.InputType.FILENAME)
        assertThat(result.valid).isFalse()
    }

    @Test fun `filename with backslash fails`() {
        val result = validator.validate("subdir\\file.txt", InputValidator.InputType.FILENAME)
        assertThat(result.valid).isFalse()
    }

    // ── SQL validation ────────────────────────────────────────────────────────

    @Test fun `safe sql query passes`() {
        val result = validator.validateSql("users")
        assertThat(result.violations.none { it.contains("SQL") }).isTrue()
    }

    @Test fun `drop table injection rejected`() {
        val result = validator.validateSql("users; DROP TABLE users")
        assertThat(result.valid).isFalse()
    }

    @Test fun `union select injection rejected`() {
        val result = validator.validateSql("1 UNION SELECT password FROM users")
        assertThat(result.valid).isFalse()
    }

    // ── JSON validation ───────────────────────────────────────────────────────

    @Test fun `valid json object passes`() {
        val result = validator.validate("""{"key": "value"}""", InputValidator.InputType.JSON)
        assertThat(result.valid).isTrue()
    }

    @Test fun `valid json array passes`() {
        val result = validator.validate("""[1, 2, 3]""", InputValidator.InputType.JSON)
        assertThat(result.valid).isTrue()
    }

    @Test fun `invalid json fails`() {
        val result = validator.validate("not json at all", InputValidator.InputType.JSON)
        assertThat(result.valid).isFalse()
        assertThat(result.violations.any { it.contains("JSON") }).isTrue()
    }

    // ── ValidationResult ─────────────────────────────────────────────────────

    @Test fun `requireValid throws on invalid`() {
        val result = validator.validateText("<script>xss</script>")
        try {
            result.requireValid()
            assert(false) { "Should have thrown" }
        } catch (e: InputValidator.SecurityValidationException) {
            assertThat(e.message).isNotEmpty()
        }
    }

    @Test fun `requireValid does not throw on valid`() {
        val result = validator.validateText("Normal safe text")
        result.requireValid()  // should not throw
    }

    @Test fun `hasViolations true when violations present`() {
        val result = validator.validateText("<script>alert(1)</script>")
        assertThat(result.hasViolations).isTrue()
    }

    @Test fun `hasViolations false when no violations`() {
        val result = validator.validateText("Safe normal text here")
        assertThat(result.hasViolations).isFalse()
    }
}
