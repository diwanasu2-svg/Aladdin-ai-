# Aladdin — Multilingual Voice Support

Complete Hindi 🇮🇳, Gujarati 🇮🇳, and English 🇬🇧 implementation across all 15 features.

---

## Quick Start

### 1. Download Models

```bash
# All languages (recommended)
chmod +x voice-core/scripts/download_models_multilingual.sh
./voice-core/scripts/download_models_multilingual.sh --dest ./models --lang all

# Hindi only
./voice-core/scripts/download_models_multilingual.sh --lang hi

# Gujarati only
./voice-core/scripts/download_models_multilingual.sh --lang gu
```

### 2. Configure (Python backend)

```bash
cp Aladdin-ai--main/.env.template Aladdin-ai--main/.env
# Edit .env to set model paths
```

The `config.yaml` is already updated with all multilingual settings.

### 3. Install optional dependencies

```bash
pip install gtts       # Gujarati TTS fallback
pip install langdetect # Enhanced language detection
```

### 4. Run tests

```bash
cd Aladdin-ai--main
python multilingual_tests.py          # all tests
python multilingual_tests.py --test=detection
python multilingual_tests.py --test=pipeline --verbose
```

### 5. Android (Kotlin)

Use `MultilingualVoiceCoreService` instead of `VoiceCoreService`:

```kotlin
val intent = MultilingualVoiceCoreService.startIntent(
    context = this,
    config = VoiceCoreConfig(),
    mlConfig = MultilingualConfig(
        defaultLanguage = "en",
        preloadVoicesOnStartup = true,
    )
)
startForegroundService(intent)
```

---

## Feature Implementation Index

| Feature | Status | Files |
|---------|--------|-------|
| F1 — Multilingual STT (Whisper/Vosk) | ✅ | `MultilingualSTTEngine.kt`, `config.yaml#whisper` |
| F2 — Multilingual LLM (Ollama) | ✅ | `multilingual_llm.py` |
| F3 — Automatic language detection | ✅ | `language_detector.py`, `LanguageDetector.kt` |
| F4 — Same-language response | ✅ | `multilingual_pipeline.py`, `multilingual_llm.py` |
| F5 — Hindi TTS (Piper) | ✅ | `multilingual_tts.py`, `MultilingualTTSEngine.kt` |
| F6 — Gujarati TTS (Piper + gTTS fallback) | ✅ | `multilingual_tts.py`, `MultilingualTTSEngine.kt` |
| F7 — Automatic TTS language switching | ✅ | `multilingual_pipeline.py`, `MultilingualTTSEngine.kt` |
| F8 — Config files updated | ✅ | `config.yaml`, `language_config.json`, `.env.template` |
| F9 — Language fallback (gu→hi→en) | ✅ | `multilingual_tts.py`, `MultilingualTTSEngine.kt` |
| F10 — Multilingual conversation memory | ✅ | `multilingual_memory.py` |
| F11 — Unicode support (UTF-8, NFC) | ✅ | `text_normalizer.py`, `multilingual_memory.py` |
| F12 — Text normalization per language | ✅ | `text_normalizer.py` |
| F13 — Voice model preloading | ✅ | `multilingual_tts.py`, `MultilingualTTSEngine.kt` |
| F14 — End-to-end tests | ✅ | `multilingual_tests.py` |
| F15 — Fallback voice handling | ✅ | `MultilingualTTSEngine.kt`, `multilingual_tts.py` |

---

## Architecture

```
User Speech (hi/gu/en)
       │
       ▼
┌─────────────────────────────┐
│  MultilingualSTTEngine.kt   │  Vosk (offline) + Whisper (optional)
│  OR Whisper (Python)        │  Auto-detects language from audio
└─────────────┬───────────────┘
              │ transcript + language
              ▼
┌─────────────────────────────┐
│  LanguageDetector           │  Unicode → Vocab → N-gram → langdetect
│  (Python & Kotlin)          │  <200ms detection latency
└─────────────┬───────────────┘
              │ detected_language
              ▼
┌─────────────────────────────┐
│  MultilingualOllamaClient   │  llama3.1/qwen2.5/gemma3
│  (multilingual_llm.py)      │  Language-specific system prompts
└─────────────┬───────────────┘
              │ response (same language)
              ▼
┌─────────────────────────────┐
│  TextNormalizer             │  Per-language danda/punctuation/
│  (text_normalizer.py)       │  contraction handling
└─────────────┬───────────────┘
              │ normalized text
              ▼
┌─────────────────────────────┐
│  MultilingualTTSEngine      │  Piper (hi/gu/en) + gTTS fallback
│  (multilingual_tts.py /     │  Fallback: gu→hi→en→AndroidTTS
│   MultilingualTTSEngine.kt) │  <100ms voice switching
└─────────────┬───────────────┘
              │ audio
              ▼
         🔊 Speaker
```

---

## Language Detection Pipeline

Detection uses a 4-stage cascade, stopping as soon as confidence ≥ 0.55:

1. **Unicode block analysis** — Devanagari (U+0900-097F) → Hindi, Gujarati script (U+0A80-0AFF) → Gujarati, Latin → English. Fastest (<1ms).
2. **Vocabulary keyword scoring** — Matches romanised Hindi/Gujarati/English vocabulary. Handles Hinglish and Gujlish.
3. **Character bigram analysis** — Distinctive letter pairs per language as tiebreaker.
4. **langdetect** (optional) — External library as last resort.

History-biased smoothing: the last 5 utterances influence detection for conversation continuity.

---

## TTS Fallback Chain

```
Gujarati requested
  → Try Piper gu_IN-cmu_indic-medium.onnx
  → If missing: try gTTS (gu)
  → If missing: try Piper hi_IN-hindi_male-medium.onnx  ← Feature 9
  → If missing: try Piper en_US-lessac-medium.onnx
  → If missing: use Android TTS (always available)       ← Feature 15
```

---

## Model Download URLs

| Model | Language | Size | Source |
|-------|----------|------|--------|
| vosk-model-small-en-us-0.15 | English STT | ~40 MB | alphacephei.com |
| vosk-model-hi-0.22 | Hindi STT | ~800 MB | alphacephei.com |
| vosk-model-gu-0.42 | Gujarati STT | ~400 MB | alphacephei.com |
| en_US-lessac-medium | English TTS | ~63 MB | HuggingFace |
| hi_IN-hindi_male-medium | Hindi TTS | ~60 MB | HuggingFace |
| gu_IN-cmu_indic-medium | Gujarati TTS | ~55 MB | HuggingFace |

All URLs are in `language_config.json → model_download_urls`.

---

## Test Scenarios (Feature 14)

### Test 1 — Hindi
```
Input:  "Aladdin, aaj ka mausam kya hai?"
Expect: Hindi STT → Language=hi (detected) → Hindi LLM reply → Piper hi TTS
```

### Test 2 — Gujarati
```
Input:  "Aladdin, aaj nu hava kaisu che?"
Expect: Gujarati STT → Language=gu → Gujarati LLM reply → Piper gu TTS (or gTTS fallback)
```

### Test 3 — English
```
Input:  "Aladdin, what is the weather today?"
Expect: English STT → Language=en → English LLM reply → Piper en TTS
```

### Test 4 — Hinglish (mixed)
```
Input:  "Aaj ka weather kaisa hai yaar?"
Expect: Detected as hi (mixed=True) → LLM handles Hinglish → Hindi TTS
```

### Test 5 — Language switching
```
Turn 1: "Aaj ka mausam kya hai?"  → hi
Turn 2: "What is the time?"        → en
Turn 3: Hindi again...             → hi (restored)
```

Run: `python multilingual_tests.py` to validate all scenarios.

---

## New Files Added

### Python (Aladdin-ai--main/)
- `language_detector.py` — Offline language detection (F3, F4, F9)
- `text_normalizer.py` — Per-language text normalization (F12, F11)
- `multilingual_tts.py` — Hindi/Gujarati/English TTS engine (F5, F6, F7, F9, F13, F15)
- `multilingual_llm.py` — Multilingual Ollama LLM client (F2, F4)
- `multilingual_memory.py` — Language-tagged conversation memory (F10, F11)
- `multilingual_pipeline.py` — End-to-end orchestrator (F3, F4, F7, F9, F10, F12)
- `multilingual_tests.py` — Complete test suite (F14)
- `language_config.json` — Language configuration (F8)
- `.env.template` — Environment variables template (F8)
- `config.yaml` — Updated with all multilingual settings (F8)
- `config.py` — Updated with multilingual dataclasses (F8)

### Kotlin/Android (voice-core/)
- `multilingual/LanguageConfig.kt` — Language config + data classes
- `multilingual/LanguageDetector.kt` — Android language detector (F3)
- `multilingual/MultilingualSTTEngine.kt` — Hindi/Gujarati/English STT (F1)
- `multilingual/MultilingualTTSEngine.kt` — Auto-switching TTS (F5-F7, F9, F13, F15)
- `multilingual/MultilingualVoiceCoreService.kt` — Full multilingual service
- `scripts/download_models_multilingual.sh` — Model download script (F13)
