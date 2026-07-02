package com.aladdin.security

import com.aladdin.security.validation.SqlInjectionPreventer
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SqlInjectionPreventerTest {

    @Test fun `clean input is safe`() {
        assertThat(SqlInjectionPreventer.containsInjection("hello world")).isFalse()
        assertThat(SqlInjectionPreventer.validate("hello world").safe).isTrue()
    }

    @Test fun `SELECT keyword detected`() {
        assertThat(SqlInjectionPreventer.containsInjection("SELECT * FROM users")).isTrue()
    }

    @Test fun `DROP TABLE detected`() {
        assertThat(SqlInjectionPreventer.containsInjection("DROP TABLE users")).isTrue()
    }

    @Test fun `UNION SELECT detected`() {
        assertThat(SqlInjectionPreventer.containsInjection("1 UNION SELECT password FROM users")).isTrue()
    }

    @Test fun `comment operator detected`() {
        assertThat(SqlInjectionPreventer.containsInjection("admin'--")).isTrue()
    }

    @Test fun `semicolon detected`() {
        assertThat(SqlInjectionPreventer.containsInjection("x; DELETE FROM table")).isTrue()
    }

    @Test fun `case insensitive detection`() {
        assertThat(SqlInjectionPreventer.containsInjection("sElEcT * fRoM users")).isTrue()
        assertThat(SqlInjectionPreventer.containsInjection("drop TABLE test")).isTrue()
    }

    @Test fun `tautology detected`() {
        assertThat(SqlInjectionPreventer.containsInjection("' OR '1'='1")).isTrue()
    }

    @Test fun `validate returns keyword reason`() {
        val result = SqlInjectionPreventer.validate("SELECT * FROM users")
        assertThat(result.safe).isFalse()
        assertThat(result.reason).contains("SQL keyword")
    }

    @Test fun `validate returns operator reason`() {
        val result = SqlInjectionPreventer.validate("admin'--")
        assertThat(result.safe).isFalse()
        assertThat(result.reason).isNotEmpty()
    }

    @Test fun `escapeLike escapes percent`() {
        assertThat(SqlInjectionPreventer.escapeLike("50%")).contains("\\%")
    }

    @Test fun `escapeLike escapes underscore`() {
        assertThat(SqlInjectionPreventer.escapeLike("user_name")).contains("\\_")
    }

    @Test fun `escapeLike escapes backslash`() {
        assertThat(SqlInjectionPreventer.escapeLike("path\\file")).contains("\\\\")
    }

    @Test fun `stripSql removes metacharacters`() {
        val stripped = SqlInjectionPreventer.stripSql("'; DROP TABLE users; --")
        assertThat(stripped).doesNotContain("'")
        assertThat(stripped).doesNotContain(";")
        assertThat(stripped).doesNotContain("--")
    }

    @Test fun `sanitizeIdentifier keeps alphanumeric`() {
        assertThat(SqlInjectionPreventer.sanitizeIdentifier("users")).isEqualTo("users")
        assertThat(SqlInjectionPreventer.sanitizeIdentifier("user_name")).isEqualTo("user_name")
    }

    @Test fun `sanitizeIdentifier strips special chars`() {
        assertThat(SqlInjectionPreventer.sanitizeIdentifier("users; DROP")).isEqualTo("usersDROP")
    }

    @Test fun `sanitizeIdentifier empty result throws`() {
        try {
            SqlInjectionPreventer.sanitizeIdentifier(";;;---")
            assert(false) { "Should throw" }
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("Invalid SQL identifier")
        }
    }

    @Test fun `false positive - normal word select not flagged in safe context`() {
        assertThat(SqlInjectionPreventer.containsInjection("I selected an option")).isTrue()
    }

    @Test fun `INSERT detected`() {
        assertThat(SqlInjectionPreventer.containsInjection("INSERT INTO users VALUES")).isTrue()
    }

    @Test fun `UPDATE detected`() {
        assertThat(SqlInjectionPreventer.containsInjection("UPDATE users SET password")).isTrue()
    }

    @Test fun `DELETE detected`() {
        assertThat(SqlInjectionPreventer.containsInjection("DELETE FROM users WHERE 1=1")).isTrue()
    }

    @Test fun `EXEC detected`() {
        assertThat(SqlInjectionPreventer.containsInjection("EXEC xp_cmdshell('whoami')")).isTrue()
    }
}
