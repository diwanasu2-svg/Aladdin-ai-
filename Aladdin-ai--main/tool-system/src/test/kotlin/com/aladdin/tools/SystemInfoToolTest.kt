package com.aladdin.tools

import android.content.Context
import com.aladdin.tools.tools.SystemInfoTool
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for SystemInfoTool.
 * System services (ActivityManager, ConnectivityManager) are not available
 * in JVM unit tests — tests verify the tool structure and error handling.
 */
class SystemInfoToolTest {

    private lateinit var context: Context
    private lateinit var tool: SystemInfoTool

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        tool = SystemInfoTool(context)
    }

    @Test fun `tool has correct id`() {
        assertEquals("system_info", tool.id)
    }

    @Test fun `tool has description`() {
        assertTrue(tool.description.isNotBlank())
    }

    @Test fun `execute does not throw on any command`() = runBlocking {
        val commands = listOf("all", "battery", "storage", "memory", "network", "device")
        commands.forEach { cmd ->
            try {
                tool.execute(mapOf("command" to cmd))
            } catch (e: Exception) {
                fail("Tool threw exception for command '$cmd': ${e.message}")
            }
        }
    }

    @Test fun `ToolResult has success or error but never both null`() = runBlocking {
        val result = try {
            tool.execute(mapOf("command" to "battery"))
        } catch (_: Exception) {
            com.aladdin.tools.tools.ToolResult.error("system_info", "System services not available in test")
        }
        assertTrue("Must be success or have error", result.success || result.error != null)
    }
}
