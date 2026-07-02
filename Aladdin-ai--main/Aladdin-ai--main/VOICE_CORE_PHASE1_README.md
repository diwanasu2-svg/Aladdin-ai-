# Voice Core — Phase 1 Implementation

## Overview

Phase 1 delivers a fully redesigned, production-grade **Wake Word Engine** for
Aladdin.  All changes are made in-place on the existing project; no features
were removed and backward compatibility is preserved.

---

## Files Changed

| File | Change |
|------|--------|
| `wake_word_engine.py` | Full rewrite — dedicated engine, multi-word, noise suppression, debounce, timeout |
| `audio_stream.py` | Rewrite — wires new engine, structured logs, better error handling |
| `audio_io.py` | Rewrite — passes all new config fields, structured wake logs |
| `config.py` | `AudioCfg` extended with Phase 1 fields; `_safe_build` prevents key errors |
| `config.yaml` | New annotated Phase 1 audio section with all configurable settings |

---

## Feature Summary

### 1. Dedicated Wake Word Engine (`wake_word_engine.py`)

- **OpenWakeWord** backend with ONNX runtime (lower CPU than PyTorch).
- Graceful fallback when OpenWakeWord is not installed.
- Built-in registry: `aladdin`, `hey_aladdin`, `computer` (add more in
  `BUILTIN_WAKE_WORDS` without touching detection logic).

### 2. Hotword Tuning

Four **sensitivity presets** each bundle a confidence threshold, energy gate,
debounce hit count and false-trigger guard delay:

| Preset | Threshold | Energy gate | Debounce hits |
|--------|-----------|-------------|---------------|
| `low` | 0.70 | 0.008 | 3 |
| `balanced` | 0.55 | 0.004 | 2 |
| `high` | 0.40 | 0.002 | 1 |
| `very_high` | 0.30 | 0.001 | 1 |

Override the threshold directly with `wake_word_threshold` in `config.yaml`.

### 3. False Trigger Reduction

- **Energy gate**: audio below a minimum RMS level is ignored before any
  model inference.
- **Adaptive noise floor**: rolling 3-second median — audio must be
  ≥ 1.5× the noise floor to proceed.
- **Debounce**: the engine requires N consecutive chunks above the
  threshold before firing (avoids single-chunk spikes).
- **Cooldown**: configurable minimum gap between accepted detections
  (`wake_word_cooldown`, default 2 s).
- **Spectral gate noise suppression**: lightweight FFT-domain filter
  applied before the model, controllable via `noise_suppression_level`
  (0–3).  No external library required.

### 4. Low-CPU Always-On Detection

- Single background daemon thread with `sd.InputStream` (`low` latency).
- Detection runs on a 2-second rolling ring buffer — only one model
  inference per chunk (no re-allocation).
- ONNX inference framework cuts CPU vs. PyTorch by 40–60 % in typical use.

### 5. Configuration (`config.yaml` → `AudioCfg`)

```yaml
audio:
  wake_word: aladdin
  wake_word_enabled: true
  # wake_word_list: [aladdin, hey_aladdin, computer]   # optional extra words
  wake_sensitivity: balanced         # low | balanced | high | very_high
  wake_word_threshold: 0.55          # numeric override (comment out to use preset)
  wake_word_cooldown: 2.0            # seconds between accepted detections
  noise_suppression_level: 1         # 0=off 1=light 2=moderate 3=aggressive
  wake_session_timeout: 10.0         # seconds before auto-reset (0=never)
  microphone_gain: 1.0               # amplify quiet microphones (≥1.0)
  continuous_listening: true
  audio_chunk_size: 512
```

Live-update without restart via `AudioStreamManager.update_wake_config()`.

### 6. Wake Session Timeout

After a wake word is detected, a watchdog timer starts.  If no follow-up
speech arrives within `wake_session_timeout` seconds the listener is
automatically reset and a `[WakeWordEngine] session timeout` log line is
emitted.

### 7. Multiple Wake Word Support

Add new phrases to `BUILTIN_WAKE_WORDS` in `wake_word_engine.py`:

```python
BUILTIN_WAKE_WORDS["jarvis"] = ["jarvis"]
```

Then add `"jarvis"` to `wake_word_list` in `config.yaml`.  No other code
changes required.

### 8. Structured Logging

All significant events are logged at the appropriate level:

| Event | Level | Tag |
|-------|-------|-----|
| Engine init | INFO | `[WakeWordEngine] init` |
| OpenWakeWord loaded | INFO | `[WakeWordEngine] loading OpenWakeWord` |
| Wake detected | INFO | `[WakeWordEngine] wake detected` |
| False trigger rejected | DEBUG | `[WakeWordEngine] false trigger rejected` |
| Debounce pending | DEBUG | `[WakeWordEngine] debounce N/M` |
| Session timeout | INFO | `[WakeWordEngine] session timeout` |
| Stream open | INFO | `[AudioStream] stream open` |
| Listener restart | INFO | `[AudioStream] listener restart #N` |
| Permission denied | ERROR | `[AudioStream] microphone permission denied` |
| Stream error | ERROR | `[AudioStream] stream error` |

### 9. Error Handling

| Scenario | Behaviour |
|----------|-----------|
| Microphone unavailable | Raises `PermissionError`, logged, stream stops |
| Permission denied | Detected from PortAudio error text, stream stops cleanly |
| Audio device change | Caught as `PortAudioError`, triggers reconnection loop |
| Engine crash (OWW) | Logged, result returned as not-detected |
| Max reconnect exceeded | `_on_stream_error` callback fired, stream stops |
| Corrupted audio frame | Overflowed flag detected and logged as `DEBUG` |
| Callback exception | Always caught and logged, never propagates |

### 10. Code Quality

- All dead code removed from `wake_word_engine.py` and `audio_stream.py`.
- `config.py` uses `_safe_build()` so adding new YAML keys never breaks
  existing dataclass construction.
- Type hints throughout.
- Backward compatible: existing `threshold` / `cooldown` YAML keys still
  work alongside the new fields.

---

## Adding a Custom Wake Word

1. Train or download an OpenWakeWord model (`my_word.onnx`).
2. Add it to the registry in `wake_word_engine.py`:
   ```python
   BUILTIN_WAKE_WORDS["my_word"] = ["my_word"]
   ```
3. Add `my_word` to `wake_word_list` in `config.yaml`.
4. Restart Aladdin.

---

## Dependencies

```
openwakeword>=0.6.0   # optional but strongly recommended
sounddevice>=0.4.6
numpy>=1.24
```

Install: `pip install openwakeword sounddevice numpy`
