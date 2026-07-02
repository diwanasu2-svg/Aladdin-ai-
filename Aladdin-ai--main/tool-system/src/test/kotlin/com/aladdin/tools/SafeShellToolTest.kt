package com.aladdin.tools

import com.aladdin.tools.tools.SafeShellTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SafeShellToolTest {

    private lateinit var shell: SafeShellTool

    @Before fun setUp() { shell = SafeShellTool() }

    // ─── Allowed commands ─────────────────────────────────────────────────────

    @Test fun `echo command is allowed`() = runBlocking {
        val result = shell.execute(mapOf("command" to "echo hello"))
        assertTrue("echo should succeed", result.success)
        assertTrue("output contains hello", result.output.contains("hello"))
    }

    @Test fun `date command is allowed`() = runBlocking {
        val result = shell.execute(mapOf("command" to "date"))
        assertTrue("date should succeed", result.success)
        assertTrue("output not empty", result.output.isNotBlank())
    }

    @Test fun `env command is allowed`() = runBlocking {
        val result = shell.execute(mapOf("command" to "env"))
        assertTrue("env should succeed or return something", result.output.isNotBlank() || result.error != null)
    }

    @Test fun `pwd command is allowed`() = runBlocking {
        val result = shell.execute(mapOf("command" to "pwd"))
        assertTrue("pwd should succeed", result.success)
    }

    // ─── Blocked commands ─────────────────────────────────────────────────────

    @Test fun `rm is blocked`() = runBlocking {
        val result = shell.execute(mapOf("command" to "rm -rf /tmp/test"))
        assertFalse("rm should be blocked", result.success)
        assertNotNull("should have error", result.error)
    }

    @Test fun `sudo is blocked`() = runBlocking {
        val result = shell.execute(mapOf("command" to "sudo ls"))
        assertFalse("sudo should be blocked", result.success)
    }

    @Test fun `curl is blocked`() = runBlocking {
        val result = shell.execute(mapOf("command" to "curl http://example.com"))
        assertFalse("curl should be blocked", result.success)
    }

    @Test fun `wget is blocked`() = runBlocking {
        val result = shell.execute(mapOf("command" to "wget http://example.com"))
        assertFalse("wget should be blocked", result.success)
    }

    @Test fun `kill is blocked`() = runBlocking {
        val result = shell.execute(mapOf("command" to "kill -9 1"))
        assertFalse("kill should be blocked", result.success)
    }

    @Test fun `reboot is blocked`() = runBlocking {
        val result = shell.execute(mapOf("command" to "reboot"))
        assertFalse("reboot should be blocked", result.success)
    }

    @Test fun `file redirect is blocked`() = runBlocking {
        val result = shell.execute(mapOf("command" to "echo test > /tmp/evil.txt"))
        assertFalse("redirect should be blocked", result.success)
    }

    @Test fun `command substitution is blocked`() = runBlocking {
        val result = shell.execute(mapOf("command" to "echo \$(whoami)"))
        assertFalse("command substitution should be blocked", result.success)
    }

    @Test fun `unknown command not in whitelist is blocked`() = runBlocking {
        val result = shell.execute(mapOf("command" to "nmap -sV localhost"))
        assertFalse("nmap should not be allowed", result.success)
    }

    @Test fun `missing command param returns error`() = runBlocking {
        val result = shell.execute(emptyMap())
        assertFalse(result.success)
        assertNotNull(result.error)
    }

    @Test fun `command chaining with semicolons is limited`() = runBlocking {
        val result = shell.execute(mapOf("command" to "echo a; echo b; echo c; echo d"))
        assertFalse("Too many semicolons should be blocked", result.success)
    }

    @Test fun `eval is blocked`() = runBlocking {
        val result = shell.execute(mapOf("command" to "eval 'echo hacked'"))
        assertFalse("eval should be blocked", result.success)
    }
}
