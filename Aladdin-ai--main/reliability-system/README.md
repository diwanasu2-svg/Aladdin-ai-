# Aladdin Reliability System

Production-grade reliability module for the Aladdin AI assistant.

## Architecture

```
reliability-system/
├── crash/          ← UncaughtExceptionHandler, crash log capture, auto-restart, state restore
├── watchdog/       ← Service watchdog timer, dead-service detection, exponential backoff
├── health/         ← 5-min health checks: CPU, memory, network, microphone
├── diagnostics/    ← Self-test suite, component validation, benchmarks, report generation
├── logging/        ← Logcat capture, daily rotation, max 10 files, gzip compression
├── performance/    ← Memory / CPU / battery tracking, report generation
├── validation/     ← Startup: dependency versions, config validation, permission checks
├── manager/        ← ReliabilityManager top-level orchestrator
└── di/             ← Hilt DI module
```

## Quick Start

```kotlin
// Application.onCreate()
class AladdinApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val reliability = ReliabilityManager(
            context             = this,
            appVersion          = BuildConfig.VERSION_NAME,
            requiredPermissions = listOf(Manifest.permission.RECORD_AUDIO)
        )
        reliability.start(
            restartIntent    = Intent(this, MainActivity::class.java),
            watchedServices  = listOf(
                ServiceWatchdog.WatchedService("VoiceCoreService", VoiceCoreService::class.java)
            )
        )
    }
}
```

## Features

### Crash Recovery
- `CrashHandler` — global `UncaughtExceptionHandler` captures stack trace, device info, memory
- `CrashLogger` — saves crash reports to `files/crashes/` (max 20 files)
- Auto-restart via `Intent` with delay (500ms → 10s) and crash-loop protection (stops after 5 rapid crashes)
- `CrashRecoveryManager` — save/restore arbitrary state JSON across crash boundaries

### Service Watchdog (Auto-Restart)
- `ServiceWatchdog` checks every 15s if watched services are still running
- Exponential backoff before each restart attempt: **5s → 10s → 30s → 1m → 5m**
- `WatchdogService` is a `START_STICKY` foreground service — OS will restart it too

### Health Checks (Every 5 Minutes)
| Check | Threshold |
|-------|-----------|
| CPU usage | warn > 85% |
| Memory usage | warn > 90% |
| Network connectivity | HTTP 204 ping to Google |
| Microphone | `AudioRecord` initialisation check |

### Diagnostics
- `DiagnosticsRunner.runFull()` — parallel component validation + performance benchmarks
- Components validated: SQLite, SharedPrefs, external storage, AudioRecord, Camera, ClassLoader, Coroutines
- Benchmarks: CPU integer ops, CPU float ops, memory allocation, string ops, file I/O
- Reports saved to `files/diagnostic_reports/` as Markdown (max 10 files)

### Rotating Logs
- Captures logcat output (main, system, crash buffers)
- Rotates daily **and** when file exceeds 10 MB
- Keeps **max 10 files**
- Old logs **gzip-compressed** automatically

### Performance Monitoring
- Samples CPU, memory, battery every 30s (configurable)
- Keeps last 120 samples in memory
- `saveReport()` writes Markdown report to `files/perf_reports/`

### Startup Validation
Run on every startup — fails fast before any risky initialisation:
1. **Permissions** — all required Android permissions are granted
2. **Dependency Versions** — Android SDK ≥ 26
3. **Storage** — internal storage is writable
4. **Memory** — ≥ 128MB available RAM

## Backoff Schedule

| Attempt | Delay |
|---------|-------|
| 1st | 5 seconds |
| 2nd | 10 seconds |
| 3rd | 30 seconds |
| 4th | 1 minute |
| 5th+ | 5 minutes |
