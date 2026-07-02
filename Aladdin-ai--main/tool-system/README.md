# Aladdin Complete Tool Calling System — Android Module

15 fully-implemented tools + ToolManager auto-invocation. Kotlin + Coroutines + Room + Hilt.

## Module Structure

```
tool-system/
├── build.gradle.kts
├── AndroidManifest.xml
├── tools/
│   ├── BaseTool.kt          — Shared interface + ToolResult
│   ├── CalculatorTool.kt    — Full expression parser (recursive-descent)
│   ├── WeatherTool.kt       — OpenWeatherMap: current + 5-day forecast + hourly
│   ├── CalendarTool.kt      — Android Calendar ContentProvider: CRUD + search
│   ├── AlarmTool.kt         — AlarmManager: set/delete/snooze/list/recurring
│   ├── TimerTool.kt         — CountDownTimer: start/pause/resume/reset + notifications
│   ├── NotesTool.kt         — Room: create/read/update/delete/search/pin + voice notes
│   ├── TodoTool.kt          — Room: tasks with priority/due dates/lists
│   ├── FileReaderTool.kt    — txt/csv/json/xml: read/head/tail/grep/info + URI support
│   ├── PdfReaderTool.kt     — PdfRenderer: page count, extract, render bitmaps
│   ├── ClipboardTool.kt     — ClipboardManager: read/write + persistent history
│   ├── SystemInfoTool.kt    — Battery/Storage/Memory/Network/Device info
│   ├── AppLauncherTool.kt   — PackageManager: launch/list/find/info (70+ known apps)
│   ├── MusicControlTool.kt  — AudioManager + MediaKeyEvent: play/pause/next/vol
│   └── SafeShellTool.kt     — Whitelisted read-only shell execution
├── manager/
│   └── ToolManager.kt       — Central dispatcher + auto-invocation + param extraction
├── db/
│   ├── entity/Entities.kt   — Room entities: Note, Todo, Alarm, Clipboard, Timer
│   ├── dao/Daos.kt          — DAOs: 5 interfaces, 35+ queries
│   └── ToolDatabase.kt      — Room database (aladdin_tools.db)
├── receiver/
│   └── AlarmReceiver.kt     — BroadcastReceiver: alarm notification + boot restore
├── service/
│   └── TimerService.kt      — Foreground service for background timers
└── di/
    └── ToolModule.kt        — Hilt SingletonComponent bindings
```

---

## The 15 Tools

### 1. Calculator
```kotlin
val result = calculator.execute(mapOf("expression" to "sqrt(144) + 2^3"))
// = 20

// Supports: + - * / % ^ (), sqrt, cbrt, abs, ln, log, log2,
//           sin, cos, tan, asin, acos, atan, sinh, cosh, tanh,
//           ceil, floor, round, exp, factorial (!),
//           pi, e, phi constants
```

### 2. Weather (OpenWeatherMap)
```kotlin
// Current weather
weather.execute(mapOf(
    "command" to "current",
    "location" to "Tokyo",
    "units" to "metric",
    "api_key" to "your_owm_key"
))

// 5-day forecast
weather.execute(mapOf("command" to "forecast", "location" to "London", "api_key" to "..."))

// Hourly (next 24h)
weather.execute(mapOf("command" to "hourly", "location" to "Paris", "api_key" to "..."))

// Auto-detect location (requires ACCESS_COARSE_LOCATION)
weather.execute(mapOf("command" to "current", "location" to "current", "api_key" to "..."))
```

**Setup:** Add your OWM key to `res/values/strings.xml` → `openweather_api_key`

### 3. Calendar (Android ContentProvider)
```kotlin
// List events for next 7 days
calendar.execute(mapOf("command" to "list", "days" to "7"))

// Create event
calendar.execute(mapOf(
    "command" to "create",
    "title" to "Team Meeting",
    "start_time" to "1718870400000",   // epoch ms
    "duration_minutes" to "60",
    "location" to "Conference Room A"
))

// Delete event
calendar.execute(mapOf("command" to "delete", "event_id" to "42"))

// Search
calendar.execute(mapOf("command" to "find", "query" to "meeting"))
```

**Requires:** `READ_CALENDAR` + `WRITE_CALENDAR` permissions

### 4. Alarm (AlarmManager)
```kotlin
// Set alarm in 30 minutes
alarm.execute(mapOf("command" to "set", "label" to "Wake up", "in_minutes" to "30"))

// Set alarm at specific time
alarm.execute(mapOf("command" to "set", "label" to "Doctor", "trigger_at" to "1718920800000"))

// Recurring alarm (Mon, Wed, Fri)
alarm.execute(mapOf("command" to "set", "label" to "Gym", "in_hours" to "6", "recurring" to "true", "repeat_days" to "MON,WED,FRI"))

// Snooze
alarm.execute(mapOf("command" to "snooze", "alarm_id" to "1", "snooze_minutes" to "10"))

// Delete / list
alarm.execute(mapOf("command" to "delete", "alarm_id" to "1"))
alarm.execute(mapOf("command" to "list"))
```

**Requires:** `SCHEDULE_EXACT_ALARM` permission (Android 12+)

### 5. Timer (CountDownTimer)
```kotlin
// Start 5-minute timer
timer.execute(mapOf("command" to "start", "label" to "Pomodoro", "duration_minutes" to "25"))

// Pause / resume / reset / stop
timer.execute(mapOf("command" to "pause",  "timer_id" to "1"))
timer.execute(mapOf("command" to "resume", "timer_id" to "1"))
timer.execute(mapOf("command" to "reset",  "timer_id" to "1"))
timer.execute(mapOf("command" to "stop",   "timer_id" to "1"))

// Observe real-time progress
timerTool.timerProgress.collect { map -> map[timerId] /* remaining ms */ }
```

### 6. Notes (Room SQLite)
```kotlin
// Create
notes.execute(mapOf("command" to "create", "title" to "Project Ideas", "content" to "Build a rocket", "tags" to "work,ideas"))

// Search
notes.execute(mapOf("command" to "search", "query" to "rocket"))

// Pin / Voice note
notes.execute(mapOf("command" to "pin", "note_id" to "1", "pin" to "true"))
notes.execute(mapOf("command" to "voice", "title" to "Voice Memo", "audio_path" to "/sdcard/memo.aac"))
```

### 7. To-Do (Room SQLite)
```kotlin
// Add task
todo.execute(mapOf("command" to "add", "title" to "Buy groceries", "priority" to "HIGH", "due_in_days" to "2"))

// Complete
todo.execute(mapOf("command" to "done", "task_id" to "1"))

// List by list name + show overdue
todo.execute(mapOf("command" to "list", "list" to "work"))
todo.execute(mapOf("command" to "overdue"))
```

### 8. File Reader
```kotlin
// Read file
fileReader.execute(mapOf("command" to "read", "path" to "/sdcard/notes.txt"))

// From file picker URI
fileReader.execute(mapOf("command" to "read", "uri" to "content://..."))

// head / tail / grep
fileReader.execute(mapOf("command" to "grep", "path" to "/sdcard/log.txt", "pattern" to "ERROR"))
fileReader.execute(mapOf("command" to "head", "path" to "/sdcard/data.csv", "lines" to "10"))
```

### 9. PDF Reader
```kotlin
// Info
pdfReader.execute(mapOf("command" to "info", "path" to "/sdcard/document.pdf"))

// Extract text (requires ML Kit or Tesseract for full OCR)
pdfReader.execute(mapOf("command" to "extract", "path" to "/sdcard/doc.pdf", "page" to "1"))
pdfReader.execute(mapOf("command" to "extract", "uri" to "content://...", "start_page" to "1", "end_page" to "5"))
```

### 10. Clipboard
```kotlin
// Read current clipboard
clipboard.execute(mapOf("command" to "read"))

// Write
clipboard.execute(mapOf("command" to "write", "text" to "Hello world"))

// History (auto-tracked on every read/write)
clipboard.execute(mapOf("command" to "history", "limit" to "20"))
clipboard.execute(mapOf("command" to "search",  "query" to "email"))
clipboard.execute(mapOf("command" to "clear",   "days" to "7"))
```

### 11. System Info
```kotlin
systemInfo.execute(mapOf("command" to "battery"))  // 🔋 Battery: 87% — Charging (AC) — 32.5°C
systemInfo.execute(mapOf("command" to "storage"))  // 💾 Internal: 45.2 GB used / 128 GB total
systemInfo.execute(mapOf("command" to "memory"))   // 🧠 4.5 GB used / 8 GB total
systemInfo.execute(mapOf("command" to "network"))  // 📡 WiFi — Internet: true — 120 Mbps down
systemInfo.execute(mapOf("command" to "device"))   // 📱 Samsung Galaxy S24 — Android 14 (API 34)
systemInfo.execute(mapOf("command" to "all"))      // Full report
```

### 12. App Launcher
```kotlin
// Launch by name (70+ pre-mapped apps)
appLauncher.execute(mapOf("command" to "launch", "app_name" to "Spotify"))
appLauncher.execute(mapOf("command" to "launch", "package_name" to "com.whatsapp"))

// List / find / info
appLauncher.execute(mapOf("command" to "list"))
appLauncher.execute(mapOf("command" to "find", "query" to "camera"))
appLauncher.execute(mapOf("command" to "info", "app_name" to "Chrome"))
```

### 13. Music Control
```kotlin
musicControl.execute(mapOf("command" to "play"))
musicControl.execute(mapOf("command" to "pause"))
musicControl.execute(mapOf("command" to "next"))
musicControl.execute(mapOf("command" to "previous"))
musicControl.execute(mapOf("command" to "volume_up"))
musicControl.execute(mapOf("command" to "volume_set", "volume" to "8"))
musicControl.execute(mapOf("command" to "mute"))
musicControl.execute(mapOf("command" to "volume", "stream" to "music"))
```

### 14. Safe Shell
```kotlin
// Allowed (read-only whitelist)
safeShell.execute(mapOf("command" to "date"))
safeShell.execute(mapOf("command" to "ls -la /sdcard"))
safeShell.execute(mapOf("command" to "cat /proc/meminfo"))
safeShell.execute(mapOf("command" to "ps | grep aladdin"))
safeShell.execute(mapOf("command" to "getprop ro.product.model"))

// Blocked (returns error)
safeShell.execute(mapOf("command" to "rm -rf /"))       // BLOCKED
safeShell.execute(mapOf("command" to "curl evil.com"))  // BLOCKED
safeShell.execute(mapOf("command" to "sudo su"))        // BLOCKED
```

### 15. ToolManager (Auto-Invocation)
```kotlin
// Auto-select tool + extract params + execute from plain text
val result = toolManager.autoInvoke("What's the weather in Tokyo?")
val result = toolManager.autoInvoke("Set a timer for 25 minutes")
val result = toolManager.autoInvoke("Calculate sqrt(256) + 5!")
val result = toolManager.autoInvoke("Add a task: buy groceries by tomorrow")
val result = toolManager.autoInvoke("Open Spotify")
val result = toolManager.autoInvoke("How much battery do I have?")

// Manual execution with explicit params
val result = toolManager.execute("calculator", mapOf("expression" to "2^32"))

// Just get tool selection without executing
val (toolId, command) = toolManager.autoSelect("Pause the music")
// → ("music_control", null)
```

---

## Setup

### 1. Include module

`settings.gradle.kts`:
```kotlin
include(":tool-system")
```

`app/build.gradle.kts`:
```kotlin
implementation(project(":tool-system"))
kapt("com.google.dagger:hilt-compiler:2.51.1")
```

### 2. Application class

```kotlin
@HiltAndroidApp
class MyApp : Application()
```

### 3. Inject and use

```kotlin
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var toolManager: ToolManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Auto-invoke from user query
        lifecycleScope.launch {
            val result = toolManager.autoInvoke("Calculate 2^10 + 24")
            println(result.output)  // "= 1048"
        }

        // Explicit tool call
        lifecycleScope.launch {
            val result = toolManager.execute("alarm", mapOf(
                "command" to "set",
                "label"   to "Morning alarm",
                "in_hours" to "8"
            ))
            println(result.output)  // ⏰ Alarm set: 'Morning alarm' at…
        }
    }
}
```

### 4. Weather API key

```xml
<!-- tool-system/src/main/res/values/strings.xml -->
<string name="openweather_api_key">YOUR_OWM_API_KEY_HERE</string>
```
Or pass `api_key` as a parameter directly.

---

## Permissions Summary

| Permission | Used by |
|---|---|
| `INTERNET` | Weather, Safe Shell |
| `ACCESS_COARSE_LOCATION` | Weather (auto-detect) |
| `READ_CALENDAR` / `WRITE_CALENDAR` | Calendar |
| `SCHEDULE_EXACT_ALARM` | Alarm |
| `POST_NOTIFICATIONS` | Timer, Alarm |
| `FOREGROUND_SERVICE` | TimerService |
| `READ_EXTERNAL_STORAGE` | File Reader, PDF Reader |
| `MODIFY_AUDIO_SETTINGS` | Music Control |

---

## Running Tests

```bash
./gradlew :tool-system:test
```

4 test files, 65+ test cases:
- `CalculatorToolTest` — 30 tests (arithmetic, functions, constants, factorial, errors)
- `ToolManagerTest` — 25 tests (auto-selection, param extraction, execution)
- `SafeShellToolTest` — 14 tests (whitelist/blocklist enforcement)
- `SystemInfoToolTest` — 4 tests (structure + JVM compatibility)
