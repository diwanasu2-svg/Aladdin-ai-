package com.aladdin.tools

import com.aladdin.tools.manager.ToolManager
import com.aladdin.tools.tools.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ToolManagerTest {

    private lateinit var manager: ToolManager
    private val calculator = CalculatorTool()
    private val weather    = mockk<WeatherTool>(relaxed = true)
    private val calendar   = mockk<CalendarTool>(relaxed = true)
    private val alarm      = mockk<AlarmTool>(relaxed = true)
    private val timer      = mockk<TimerTool>(relaxed = true)
    private val notes      = mockk<NotesTool>(relaxed = true)
    private val todo       = mockk<TodoTool>(relaxed = true)
    private val fileReader = mockk<FileReaderTool>(relaxed = true)
    private val pdfReader  = mockk<PdfReaderTool>(relaxed = true)
    private val clipboard  = mockk<ClipboardTool>(relaxed = true)
    private val systemInfo = mockk<SystemInfoTool>(relaxed = true)
    private val appLauncher= mockk<AppLauncherTool>(relaxed = true)
    private val musicControl = mockk<MusicControlTool>(relaxed = true)
    private val safeShell  = mockk<SafeShellTool>(relaxed = true)

    @Before
    fun setUp() {
        manager = ToolManager(
            calculator, weather, calendar, alarm, timer, notes, todo,
            fileReader, pdfReader, clipboard, systemInfo, appLauncher, musicControl, safeShell
        )
    }

    // ─── Auto-selection ───────────────────────────────────────────────────────

    @Test fun `selects calculator for math query`() {
        val (toolId, _) = manager.autoSelect("calculate 2 + 3") ?: fail("Should match")
        assertEquals("calculator", toolId)
    }

    @Test fun `selects weather for weather query`() {
        val (toolId, _) = manager.autoSelect("What's the weather in London?") ?: fail("Should match")
        assertEquals("weather.fetch", toolId)
    }

    @Test fun `selects alarm for alarm query`() {
        val (toolId, _) = manager.autoSelect("Set an alarm for 7am") ?: fail("Should match")
        assertEquals("alarm", toolId)
    }

    @Test fun `selects timer for timer query`() {
        val (toolId, _) = manager.autoSelect("Start a timer for 10 minutes") ?: fail("Should match")
        assertEquals("timer", toolId)
    }

    @Test fun `selects notes for note query`() {
        val (toolId, _) = manager.autoSelect("Create a note about my meeting") ?: fail("Should match")
        assertEquals("notes", toolId)
    }

    @Test fun `selects todo for task query`() {
        val (toolId, _) = manager.autoSelect("Add a task to buy groceries") ?: fail("Should match")
        assertEquals("todo", toolId)
    }

    @Test fun `selects system_info for battery query`() {
        val (toolId, _) = manager.autoSelect("How much battery do I have?") ?: fail("Should match")
        assertEquals("system_info", toolId)
    }

    @Test fun `selects app_launcher for open app`() {
        val (toolId, _) = manager.autoSelect("Open Spotify") ?: fail("Should match")
        assertEquals("app_launcher", toolId)
    }

    @Test fun `selects music_control for pause music`() {
        val (toolId, _) = manager.autoSelect("Pause music") ?: fail("Should match")
        assertEquals("music_control", toolId)
    }

    @Test fun `selects clipboard for clipboard read`() {
        val (toolId, _) = manager.autoSelect("What's in my clipboard?") ?: fail("Should match")
        assertEquals("clipboard", toolId)
    }

    @Test fun `selects file_reader for file read`() {
        val (toolId, _) = manager.autoSelect("Read file /sdcard/test.txt") ?: fail("Should match")
        assertEquals("file_reader", toolId)
    }

    @Test fun `selects pdf_reader for pdf query`() {
        val (toolId, _) = manager.autoSelect("Open the PDF document.pdf") ?: fail("Should match")
        assertEquals("pdf_reader", toolId)
    }

    @Test fun `selects safe_shell for shell command`() {
        val (toolId, _) = manager.autoSelect("Run shell command: ls -la") ?: fail("Should match")
        assertEquals("safe_shell", toolId)
    }

    // ─── Parameter extraction ─────────────────────────────────────────────────

    @Test fun `extracts calculator expression`() {
        val params = manager.autoExtractParams("Calculate 15 * 4 + 8", "calculator")
        assertEquals("15 * 4 + 8", params["expression"])
    }

    @Test fun `extracts weather location`() {
        val params = manager.autoExtractParams("What's the weather in Paris?", "weather.fetch")
        assertEquals("Paris", params["location"])
    }

    @Test fun `extracts alarm duration in minutes`() {
        val params = manager.autoExtractParams("Set an alarm in 30 minutes", "alarm")
        assertEquals("30", params["in_minutes"])
    }

    @Test fun `extracts timer duration in minutes`() {
        val params = manager.autoExtractParams("Start a timer for 5 minutes", "timer")
        assertEquals("5", params["duration_minutes"])
    }

    @Test fun `extracts timer duration in seconds`() {
        val params = manager.autoExtractParams("Set a 90 second timer", "timer")
        assertEquals("90", params["duration_seconds"])
    }

    @Test fun `extracts note title`() {
        val params = manager.autoExtractParams("Create a note about project ideas", "notes")
        assertNotNull(params["title"])
        assertTrue(params["title"]?.contains("project") == true)
    }

    @Test fun `extracts task priority critical`() {
        val params = manager.autoExtractParams("Add urgent task: submit report", "todo")
        assertEquals("CRITICAL", params["priority"])
    }

    @Test fun `extracts app name`() {
        val params = manager.autoExtractParams("Open Gmail", "app_launcher")
        assertEquals("Gmail", params["app_name"])
    }

    @Test fun `extracts volume level`() {
        val params = manager.autoExtractParams("Set volume to 10", "music_control")
        assertEquals("10", params["volume"])
    }

    @Test fun `extracts shell command`() {
        val params = manager.autoExtractParams("Run command: ls -la", "safe_shell")
        assertEquals("ls -la", params["command"])
    }

    // ─── Execution ────────────────────────────────────────────────────────────

    @Test fun `execute calculator directly returns result`() = runBlocking {
        val result = manager.execute("calculator", mapOf("expression" to "2^10"))
        assertTrue(result.success)
        assertTrue(result.output.contains("1024"))
    }

    @Test fun `execute unknown tool returns error`() = runBlocking {
        val result = manager.execute("nonexistent_tool", emptyMap())
        assertFalse(result.success)
        assertNotNull(result.error)
    }

    @Test fun `list tools returns all 14 tools`() {
        val list = manager.listTools()
        assertTrue("Should mention calculator", list.contains("calculator"))
        assertTrue("Should mention weather", list.contains("weather"))
        assertTrue("Should mention alarm", list.contains("alarm"))
        assertTrue("Should mention timer", list.contains("timer"))
        assertTrue("Should mention notes", list.contains("notes"))
        assertTrue("Should mention todo", list.contains("todo"))
    }

    @Test fun `forecast weather command extracted`() {
        val params = manager.autoExtractParams("What's the 5-day weather forecast for Tokyo?", "weather.fetch")
        assertEquals("forecast", params["command"])
    }

    @Test fun `music pause command extracted`() {
        val params = manager.autoExtractParams("Pause the music please", "music_control")
        assertEquals("pause", params["command"])
    }

    @Test fun `todo overdue command extracted`() {
        val params = manager.autoExtractParams("Show me overdue tasks", "todo")
        assertEquals("overdue", params["command"])
    }
}
