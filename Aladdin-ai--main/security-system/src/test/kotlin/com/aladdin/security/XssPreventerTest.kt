package com.aladdin.security

import com.aladdin.security.validation.XssPreventer
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class XssPreventerTest {

    @Test fun `plain text has no XSS`() {
        assertThat(XssPreventer.containsXss("Hello, how are you?")).isFalse()
    }

    @Test fun `script tag detected`() {
        assertThat(XssPreventer.containsXss("<script>alert('xss')</script>")).isTrue()
    }

    @Test fun `script tag case insensitive`() {
        assertThat(XssPreventer.containsXss("<SCRIPT>alert(1)</SCRIPT>")).isTrue()
    }

    @Test fun `javascript protocol detected`() {
        assertThat(XssPreventer.containsXss("javascript:alert(1)")).isTrue()
    }

    @Test fun `onclick event detected`() {
        assertThat(XssPreventer.containsXss("<img onclick='alert(1)' src=x>")).isTrue()
    }

    @Test fun `onerror event detected`() {
        assertThat(XssPreventer.containsXss("<img src=x onerror=alert(1)>")).isTrue()
    }

    @Test fun `iframe detected`() {
        assertThat(XssPreventer.containsXss("<iframe src='evil.com'></iframe>")).isTrue()
    }

    @Test fun `sanitize removes script tags`() {
        val sanitized = XssPreventer.sanitize("<script>evil()</script>hello")
        assertThat(sanitized).doesNotContain("<script>")
        assertThat(sanitized).contains("hello")
    }

    @Test fun `sanitize preserves safe HTML`() {
        val safe = "Just regular text with no tags"
        assertThat(XssPreventer.sanitize(safe)).isEqualTo(safe)
    }

    @Test fun `empty string is safe`() {
        assertThat(XssPreventer.containsXss("")).isFalse()
    }

    @Test fun `blank string is safe`() {
        assertThat(XssPreventer.containsXss("   ")).isFalse()
    }

    @Test fun `data uri detected`() {
        assertThat(XssPreventer.containsXss("data:text/html,<script>alert(1)</script>")).isTrue()
    }

    @Test fun `vbscript detected`() {
        assertThat(XssPreventer.containsXss("vbscript:msgbox(1)")).isTrue()
    }

    @Test fun `expression detected`() {
        assertThat(XssPreventer.containsXss("style=\"expression(alert(1))\"")).isTrue()
    }
}
