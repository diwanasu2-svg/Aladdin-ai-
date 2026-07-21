# Aladdin AI Assistant — Deployment Setup Guide

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Python | 3.10+ | Main AI engine |
| Android Studio | Flamingo+ | Android build |
| Gradle | 8.x | Auto-provisioned via wrapper |
| JDK | 17 | Required by Gradle |
| Ollama | Latest | Local LLM runtime |

---

## 1 — Clone and configure secrets

```bash
git clone https://github.com/your-org/aladdin.git
cd aladdin
```

**Copy secret templates and fill in real values:**

```bash
# Android signing
cp config/keystore.template.properties keystore.properties
# Edit keystore.properties — add real passwords and keystore path

# Firebase (optional — for Crashlytics / FCM)
cp config/google-services.template.json app/google-services.json
# Edit app/google-services.json — paste real JSON from Firebase Console

# Python secrets
cp config/secrets.template.properties secrets.properties
# Edit secrets.properties — add Gemini API key, Porcupine access key, etc.
```

> ⚠️ **Never commit the real `keystore.properties`, `google-services.json`, or `secrets.properties`.**  
> These files are already in `.gitignore`.

---

## 2 — Porcupine wake-word files

1. Sign in at [console.picovoice.ai](https://console.picovoice.ai/).
2. Create a wake-word model for "Aladdin" (or download a pre-trained one).
3. Copy the following files to `Aladdin-ai--main/models/porcupine/`:
   - `porcupine_params.pv`
   - `Aladdin.ppn`
   - *(put your access key in `secrets.properties` as `PORCUPINE_ACCESS_KEY`)*

---

## 3 — Python environment setup

```bash
cd Aladdin-ai--main

# Create and activate a virtual environment
python -m venv .venv
source .venv/bin/activate   # Windows: .venv\Scripts\activate

# Install dependencies
pip install -r requirements.txt

# Verify VAD (ONNX)
python -c "from silero_vad import load_silero_vad; m = load_silero_vad(onnx=True); print('VAD OK')"

# Run assistant (text mode — no mic/speaker required)
python main.py --text
```

---

## 4 — Android build

```bash
# From project root
./gradlew assembleDebug       # debug APK
./gradlew bundleRelease       # signed release AAB (requires keystore.properties)
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`  
AAB output: `app/build/outputs/bundle/release/app-release.aab`

---

## 5 — CI/CD pipeline

The GitHub Actions workflow at `.github/workflows/ci.yml` runs automatically on every push and pull request:

| Job | Trigger | Description |
|-----|---------|-------------|
| lint | all branches | pylint, black, mypy |
| unit-tests | after lint | pytest + coverage |
| integration-tests | after unit tests | pytest integration suite |
| android-build | after unit tests | debug APK + unsigned release AAB |
| security | after lint | pip-audit + Bandit SAST |
| release | on GitHub Release | signed APK + AAB, auto-release notes |

**Required GitHub Secrets for release job:**

| Secret | Description |
|--------|-------------|
| `KEYSTORE_BASE64` | Base64-encoded `.jks` file (`base64 -i release.jks`) |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |
| `GOOGLE_SERVICES_JSON` | Full content of `google-services.json` |

---

## 6 — Pipeline architecture (post-fixes)

```
Microphone
  │
  ▼
VAD (Silero ONNX — single consistent implementation)
  │
  ▼
LowLatencyPipeline  ← audio_normalise processor, async, target <500 ms
  │
  ▼
BargeInManager (EchoCanceller + BargeInDetector — unified, no duplicates)
  │  └── triggers TTS interrupt on speech detection during playback
  ▼
StreamingSTT → IntentDetector → PipelineOrchestrator
  │                                │
  │                        LowLatencyPipeline stats
  ▼
LLM (Ollama) → ToolExecutor → StreamingTTS
  │
  ▼
LowLatencyOutput → Speaker
```

---

## 7 — Environment variables reference

All config values can be overridden via environment variables or `secrets.properties`:

| Variable | Default | Description |
|----------|---------|-------------|
| `GEMINI_API_KEY` | — | Google Gemini API key |
| `PORCUPINE_ACCESS_KEY` | — | Picovoice Porcupine key |
| `OLLAMA_HOST` | `http://localhost:11434` | Ollama endpoint |
| `OLLAMA_MODEL` | `llama3` | Model to use |
| `ALADDIN_LOG_LEVEL` | `INFO` | Log verbosity |
