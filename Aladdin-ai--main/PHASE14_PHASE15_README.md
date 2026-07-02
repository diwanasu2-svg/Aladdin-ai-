# Aladdin AI — Phase 14 (AI Models) & Phase 15 (Production)

## Overview

This update adds **10 AI Model features** (Phase 14) and **10 Production features** (Phase 15)
to the Aladdin AI project, making it enterprise-ready with optimized local inference,
comprehensive testing, and full Play Store deployment capability.

---

## Phase 14 — AI Models

| Feature | Module | Status |
|---|---|---|
| Local LLM (llama.cpp) | `models/local_llm.py` | ✅ |
| MLC-LLM (GPU/NPU) | `models/mlc_llm.py` | ✅ |
| Provider Auto-Switch | `models/provider_switcher.py` | ✅ |
| Streaming Everywhere | `models/streaming_manager.py` | ✅ |
| Fallback Chain | `models/fallback_chain.py` | ✅ |
| Model Caching | `models/model_cache.py` | ✅ |
| Quantized Models | `models/quantization.py` | ✅ |
| GPU Acceleration | `models/gpu_accelerator.py` | ✅ |
| Prompt Optimization | `models/prompt_optimizer.py` | ✅ |
| Token Optimization | `models/token_optimizer.py` | ✅ |

### Quick Start

```python
from phase14_phase15_integration import initialise_ai_and_production

systems = initialise_ai_and_production(
    config={
        "system_prompt": "You are Aladdin, a helpful AI assistant.",
        "model_family": "ollama",
        "openai_api_key": "",  # optional
    },
    enable_local_llm=True,
    enable_mlc=False,       # set True if MLC-LLM is installed
    enable_gpu=True,
)

llm = systems["llm"]

# Simple chat
reply = llm.chat("What is the capital of France?")
print(reply)

# Streaming
session = llm.stream("Tell me a story", ui_callback=lambda c: print(c.text, end="", flush=True))
session.wait()

# Status
import json; print(json.dumps(llm.status(), indent=2))
```

### Model Download

```python
from models.local_llm import LocalLLM

llm = LocalLLM(model_dir="models/gguf")

# Download recommended model for your device
llm.download_model(progress_callback=lambda pct: print(f"{pct*100:.0f}%"))

# Or download a specific model
llm.download_model("phi3-mini-q4")    # 2.2 GB — good for phones
llm.download_model("mistral-7b-q4")  # 4.4 GB — good for tablets/desktop

# List available models
print(llm.list_downloaded())
```

### Provider Priority

Default priority (lowest number = highest priority):

| Priority | Provider | Notes |
|---|---|---|
| 1 | Gemini | Cloud — fastest, requires internet |
| 2 | OpenAI | Cloud — most capable |
| 3 | Anthropic | Cloud — Claude models |
| 4 | Ollama | Local server |
| 5 | MLC-LLM | On-device, GPU-accelerated |
| 6 | Local LLM | On-device, CPU/GPU |
| 99 | Rule-based | Ultimate fallback |

Auto-switches when a provider fails or goes offline.

---

## Phase 15 — Production

| Feature | Module | Status |
|---|---|---|
| Unit Tests | `production/test_unit/` | ✅ |
| Integration Tests | `production/test_integration/` | ✅ |
| UI Tests | `production/test_ui/` | ✅ |
| Performance Tests | `production/test_performance/` | ✅ |
| Battery Optimization | `production/battery_manager.py` | ✅ |
| Memory Optimization | `production/memory_manager.py` | ✅ |
| APK Shrinking | `production/apk_optimizer.py` | ✅ |
| Crashlytics | `production/crashlytics_integration.py` | ✅ |
| CI/CD Pipeline | `production/ci_cd_pipeline.py` | ✅ |
| Play Store Ready | `production/play_store_ready.py` | ✅ |

### Running Tests

```bash
# Navigate to project root
cd Aladdin-ai--main

# Install test dependencies
pip install -r requirements_phase14_phase15.txt

# Run all unit tests
python -m pytest production/test_unit/ -v --cov=. --cov-report=term-missing

# Run integration tests
python -m pytest production/test_integration/ -v

# Run performance tests (with benchmarks)
python -m pytest production/test_performance/ -v --benchmark-json=benchmark.json

# Run all tests with coverage report
python -m pytest production/ -v --cov=. --cov-report=html --cov-fail-under=75
```

### Battery Optimization

```python
from production.battery_manager import BatteryManager

battery = BatteryManager()
battery.start_monitoring()

# Register a callback for mode changes
def on_mode_change(state, mode):
    print(f"Battery: {state.level_pct:.0f}%  Mode: {mode.value}")
    if mode.value == "ultra_saver":
        llm.cache = None  # disable cache in ultra-saver

battery.register_callback(on_mode_change)

# Check before running expensive tasks
if battery.ai_inference_allowed():
    reply = llm.chat("Long complex query...")
else:
    reply = "Battery critically low — simple mode only"
```

### Crash Reporting

```python
from production.crashlytics_integration import CrashlyticsIntegration

crash = CrashlyticsIntegration(app_version="14.15.0")
crash.set_user_id("user_12345")
crash.set_custom_key("provider", "ollama")

# Record non-fatal
try:
    result = risky_operation()
except Exception as e:
    crash.record_non_fatal(e, context="risky_operation")

# Fatal crashes are captured automatically via sys.excepthook

# View summary
print(crash.crash_summary())
```

### Play Store Preparation

```bash
python -c "
from production.play_store_ready import PlayStoreReadiness
from production.apk_optimizer import APKOptimizer

# Generate all store assets
ps = PlayStoreReadiness('.')
ps.generate_store_assets()

# Generate ProGuard rules and build config
apk = APKOptimizer('.')
apk.setup_all()

# Check readiness
passed, checks = ps.run_all_checks()
print('Play Store ready!' if passed else 'Issues found — see above')
"
```

### CI/CD Setup

```bash
python -c "
from production.ci_cd_pipeline import CICDPipeline
ci = CICDPipeline('.')
ci.write_all()
print('Required GitHub Secrets:')
for s in ci.validate_secrets_list():
    print(f'  - {s}')
"
```

---

## File Structure

```
Aladdin-ai--main/
├── models/                          # Phase 14 — AI Models
│   ├── __init__.py
│   ├── llm_manager.py               # Central orchestration
│   ├── local_llm.py                 # llama.cpp + GGUF models
│   ├── mlc_llm.py                   # MLC-LLM GPU inference
│   ├── provider_switcher.py         # Auto-switch between providers
│   ├── streaming_manager.py         # Token streaming (SSE, WS, callback)
│   ├── fallback_chain.py            # Multi-level fallback
│   ├── model_cache.py               # LRU + persistent cache
│   ├── quantization.py              # GGUF quantization levels
│   ├── gpu_accelerator.py           # GPU/NPU detection & management
│   ├── prompt_optimizer.py          # Prompt building + context trimming
│   └── token_optimizer.py           # Token budget + conversation summarization
│
├── production/                      # Phase 15 — Production
│   ├── __init__.py
│   ├── battery_manager.py           # Battery & wake lock optimization
│   ├── memory_manager.py            # Memory leak detection & GC
│   ├── apk_optimizer.py             # ProGuard + AAB/APK split config
│   ├── crashlytics_integration.py   # Firebase Crashlytics + local fallback
│   ├── ci_cd_pipeline.py            # GitHub Actions workflows
│   ├── play_store_ready.py          # Play Store submission checklist
│   ├── test_unit/
│   │   ├── test_voice.py
│   │   ├── test_memory.py
│   │   ├── test_tools.py
│   │   ├── test_reasoning.py
│   │   └── test_vision.py
│   ├── test_integration/
│   │   ├── test_pipeline.py         # Voice→STT→LLM→TTS pipeline
│   │   └── test_api.py              # HTTP API integration
│   ├── test_ui/
│   │   └── test_compose.py          # Compose UI + ViewModel logic
│   └── test_performance/
│       └── test_performance.py      # p50/p90/p99 + load tests
│
├── phase14_phase15_integration.py   # Single wiring point
├── requirements_phase14_phase15.txt # All new dependencies
└── PHASE14_PHASE15_README.md        # This file
```

---

## Dependencies Installation

```bash
# Core Phase 14 dependencies
pip install -r Aladdin-ai--main/requirements_phase14_phase15.txt

# llama.cpp with GPU (choose ONE):
CMAKE_ARGS="-DLLAMA_CUBLAS=on"  pip install llama-cpp-python  # CUDA
CMAKE_ARGS="-DLLAMA_VULKAN=on"  pip install llama-cpp-python  # Vulkan (Android/Linux)
CMAKE_ARGS="-DLLAMA_METAL=on"   pip install llama-cpp-python  # Apple Silicon

# MLC-LLM (optional, for GPU inference):
pip install --pre -f https://mlc.ai/wheels mlc-llm-nightly-cpu  # CPU
# pip install --pre -f https://mlc.ai/wheels mlc-llm-nightly-cu121  # CUDA
```

---

## KPI Status

| KPI | Status |
|---|---|
| Local LLM (llama.cpp) on Android | ✅ Implemented |
| MLC LLM with GPU acceleration | ✅ Implemented |
| Provider auto-switch | ✅ Implemented |
| Streaming everywhere (text + voice) | ✅ Implemented |
| Fallback chain (cloud → local → fallback) | ✅ Implemented |
| Model caching | ✅ Implemented |
| Quantized models | ✅ Implemented |
| GPU acceleration with CPU fallback | ✅ Implemented |
| Prompt optimization | ✅ Implemented |
| Token optimization | ✅ Implemented |
| Unit tests (80%+ coverage target) | ✅ Implemented |
| Integration tests | ✅ Implemented |
| UI tests | ✅ Implemented |
| Performance tests (p50/p90/p99) | ✅ Implemented |
| Battery optimization | ✅ Implemented |
| Memory optimization | ✅ Implemented |
| APK shrinking | ✅ Implemented |
| Crashlytics integrated | ✅ Implemented |
| CI/CD pipeline | ✅ Implemented |
| Play Store ready build | ✅ Implemented |
