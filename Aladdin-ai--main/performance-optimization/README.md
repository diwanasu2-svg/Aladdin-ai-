# Aladdin Performance Optimization Module

Production-grade performance optimization for the Aladdin AI assistant.

## Performance Targets

| Metric | Target | Monitored By |
|--------|--------|--------------|
| Startup time | **< 3 000 ms** | `StartupTracker` |
| Average CPU | **< 10 %** | `CpuOptimizer` |
| Peak RAM | **< 200 MB** | `MemoryOptimizer` |
| LRU cache hit | > 80 % | `LruCacheManager` |
| Object pool hit | > 90 % | `ObjectPool` |

## Architecture

```
performance-optimization/
├── cache/            ← Two-tier (RAM + disk) model caches: Whisper, Piper, Embedding
├── threading/        ← 4 dedicated thread pools (audio, ML, network, main)
├── memory/           ← MemoryOptimizer (ComponentCallbacks2), LRU caches, object pools, leak detector
├── startup/          ← StartupTracker, LazyServiceInitializer (critical + deferred waves), splash screen
├── cpu/              ← CpuOptimizer (rolling avg, WakeLock), TaskThrottler, BatchProcessor
├── pipeline/         ← 4-stage zero-copy pipeline, BufferPool (direct ByteBuffer), StageBatcher
├── async/            ← AsyncProcessor (non-blocking fan-out), StreamingFlow utilities
├── benchmark/        ← PerformanceBenchmarkSuite (10 tests), BenchmarkReport (Markdown)
├── manager/          ← PerformanceOptimizationManager (top-level orchestrator)
└── di/               ← Hilt singleton module
```

## Quick Start

```kotlin
// Application.onCreate()
class AladdinApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val perf = PerformanceOptimizationManager(this)
        lifecycleScope.launch { perf.start() }

        // Register services
        perf.startupOptimizer.registerCriticalService("VoiceCore") { voiceCore.init() }
        perf.startupOptimizer.registerDeferredService("Analytics") { analytics.init() }
    }
}

// MainActivity.onCreate()
perf.startupOptimizer.onActivityCreate(this)  // installs splash screen + fires deferred init

// Run benchmarks (e.g. from dev menu)
val report = perf.runBenchmarks()
Log.i("Perf", report.toMarkdown())
```

## Thread Pools

| Pool | Threads | Android Priority | Use |
|------|---------|-----------------|-----|
| `audio` | 2 | `URGENT_AUDIO` (−16) | PCM capture, TTS playback |
| `ml` | 2–4 | `DEFAULT` (0) | Whisper ASR, LLM, Piper TTS |
| `network` | 4–8 | `BACKGROUND` (10) | HTTP, WebSocket, cloud sync |
| UI | — | Main thread | View updates only |

## Model Caching

Two-tier strategy for all ML models:

```
Request ──► RAM (LRU) ──► Disk cache ──► Asset/Filesystem
              80 MB           500 MB
```

| Cache | RAM Budget | Disk Budget |
|-------|-----------|-------------|
| Whisper GGUF | 80 MB | 500 MB |
| Piper ONNX | 40 MB | 200 MB |
| Embeddings | 30 MB + 1 000 vectors | 100 MB |

## Memory Strategy

`MemoryOptimizer` registers as a `ComponentCallbacks2` and reacts to system trim signals:

| Signal | Action |
|--------|--------|
| `RUNNING_LOW` | Trim LRU caches to 50% |
| `RUNNING_CRITICAL` | Clear all caches + suggest GC |
| `UI_HIDDEN` | Release audio/TTS buffers |
| `COMPLETE` | Emergency full eviction |

## Startup Sequence (target < 3 s)

```
T+0ms    Application.onCreate()
T+0ms    PerformanceOptimizationManager.start()
T+0ms    Critical services (VoiceCore, AI engine)
T+~400ms  MainActivity.onCreate() — splash screen installed
T+~800ms  Splash dismissed (critical init done)
T+~900ms  AppReady milestone logged
T+~1900ms Deferred services start (analytics, health checks…)
```

## Benchmarks

Run `perf.runBenchmarks()` to validate all 10 targets:

```
| Test            | Score    | Unit   | Target  |
|-----------------|----------|--------|---------|
| CPU Integer Ops | ≥ 200    | Mops/s | 200     |
| CPU Float Ops   | ≥ 100    | Mops/s | 100     |
| Memory Alloc    | ≥ 0.5    | GB/s   | 0.5     |
| Disk Write      | ≥ 30     | MB/s   | 30      |
| Disk Read       | ≥ 50     | MB/s   | 50      |
| LRU Cache Hit   | ≥ 80     | %      | 80      |
| Object Pool Hit | ≥ 90     | %      | 90      |
| String Ops      | ≥ 50     | Kops/s | 50      |
| RAM Usage       | < 200    | MB     | —       |
```
