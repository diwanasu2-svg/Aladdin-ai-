# Aladdin AI Assistant — Build Instructions

## Quick Start

```bash
# 1. Clone / extract
cd Aladdin-Complete-Bundle

# 2. Download AI models (Whisper, Piper, Ollama)
bash scripts/download_models.sh --whisper base --piper en_US-amy-medium

# 3. Install Python dependencies
pip install -r Aladdin-ai--main/requirements.txt

# 4. Start Ollama (local LLM)
ollama serve &
ollama pull llama3

# 5. Run Aladdin (voice mode)
cd Aladdin-ai--main && python main.py
```

---

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Python | 3.11+ | 3.12 also supported |
| Java | 17 | Temurin recommended |
| Android SDK | 33+ | For APK build |
| Ollama | latest | Local LLM server |
| Piper | 1.2.0 | Auto-downloaded |
| Whisper/FasterWhisper | any | Auto-downloaded |

---

## Phase-by-Phase Build Guide

### Phase 1 — Streaming TTS

```bash
# Verify Piper binary
models/piper/piper --version

# Test TTS
python -c "
from streaming_tts import StreamingTTS
tts = StreamingTTS(model_path='voices/en_US-amy-medium.onnx')
for chunk in tts.synthesize_streaming('Hello world'):
    print(f'chunk: {len(chunk)} samples')
"
```

### Phase 2 — Streaming STT

```bash
pip install faster-whisper
python -c "
from streaming_stt import StreamingSTT
stt = StreamingSTT(engine='faster-whisper', model='base')
stt.start()
import time; time.sleep(2)
stt.stop()
print('STT OK')
"
```

### Phase 3 — Pipeline

```bash
python -c "
from pipeline_orchestrator import PipelineOrchestrator
from unittest.mock import MagicMock
llm = MagicMock(); llm.chat.return_value = 'Hello!'
mem = MagicMock(); mem.recent.return_value = []
orch = PipelineOrchestrator(llm=llm, memory=mem)
r = orch.process('Hello')
print('Response:', r.response)
"
```

### Phase 4 — Full Duplex

```bash
pip install sounddevice
python -c "
from full_duplex import FullDuplexAudioManager
mgr = FullDuplexAudioManager(enabled=True)
mgr.start()
import time; time.sleep(2)
mgr.stop()
print('Full duplex OK')
"
```

### Phase 5 — Messaging

Set credentials in `config.yaml`:
```yaml
messaging:
  telegram:
    token: "YOUR_BOT_TOKEN"
  whatsapp:
    account_sid: "..."
    auth_token: "..."
  discord:
    token: "..."
  fcm:
    server_key: "..."
```

### Phase 6 — Search

```bash
python -c "
from search import InternetSearch, needs_search
s = InternetSearch()
print(s.answer('Python programming language'))
"
```

### Phase 7 — Run All Tests

```bash
cd Aladdin-ai--main
pytest tests/test_pipeline.py -v --timeout=120
```

### Phase 8 — Android APK

```bash
# Debug build
./gradlew :app:assembleDebug

# Release build (requires signing keys)
export KEYSTORE_FILE=keystore.jks
./gradlew :app:assembleRelease :app:bundleRelease
```

### Phase 9 — Performance

```bash
python -c "
from performance_optimizer import log_resource_stats, warm_up_async
t = warm_up_async('base')
t.join()
log_resource_stats()
"
```

### Phase 10 — Reliability

```bash
python -c "
from reliability import validate_dependencies, setup_rotating_logs
setup_rotating_logs()
ok, warnings = validate_dependencies()
print('Dependencies OK:', ok)
print('Warnings:', warnings)
"
```

### Phase 11 — CI/CD

Push to GitHub — CI runs automatically.

Manual trigger:
```bash
gh workflow run ci.yml
```

---

## Android APK Signing

1. Generate keystore:
```bash
keytool -genkeypair -v -keystore keystore.jks \
  -alias aladdin -keyalg RSA -keysize 2048 -validity 10000
```

2. Add to GitHub Secrets:
   - `KEYSTORE_FILE` — base64-encoded keystore
   - `KEY_ALIAS` — alias
   - `KEY_PASSWORD` — key password
   - `STORE_PASSWORD` — store password

3. Build release APK via CI.

---

## Configuration Reference (`config.yaml`)

```yaml
ollama:
  host: http://localhost:11434
  model: llama3
  timeout: 60

piper:
  model_path: voices/en_US-amy-medium.onnx

whisper:
  model: base        # tiny | base | small | medium | large
  device: cpu        # cpu | cuda

audio:
  wake_word: aladdin
  wake_word_enabled: true
  interrupt_enabled: true
  full_duplex: true
  barge_in_threshold: 0.05

search:
  enabled: true
  provider: duckduckgo   # duckduckgo | brave | google
  max_results: 5
  timeout: 8
  cache_ttl: 600

logging:
  level: INFO
  file: logs/aladdin.log
```

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| Piper not found | Run `scripts/download_models.sh` |
| Ollama not reachable | Run `ollama serve` |
| No microphone | Check `sounddevice` install and audio permissions |
| Wake word not triggering | Lower `vad_threshold` in config |
| High RAM usage | Set `whisper.model: tiny` in config |
| APK build fails | Ensure `JAVA_HOME` points to JDK 17 |
