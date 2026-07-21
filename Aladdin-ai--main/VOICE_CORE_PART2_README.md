# Voice Core - Complete Implementation (Part 1 + Part 2)

## Overview

This document describes the complete Voice Core implementation in Aladdin, including both Part 1 (Wake Word + Continuous Listening) and Part 2 (VAD, Noise Suppression, Echo Cancellation, Auto-Resume).

## Part 1: Wake Word & Continuous Listening (Previously Implemented)

### Features
- **Wake Word Detection**: OpenWakeWord integration with configurable phrases
- **Continuous Listening**: Background thread-based microphone monitoring
- **Streaming Audio**: Low-latency audio chunks for STT integration

**Configuration**:
```yaml
audio:
  wake_word: aladdin
  wake_word_enabled: true
  wake_word_threshold: 0.5
  wake_word_cooldown: 1.0
  continuous_listening: true
  audio_chunk_size: 512
```

---

## Part 2: Advanced Voice Features (NEW)

### 1. Voice Activity Detection (VAD)

**Files**: `aladdin_core/vad.py`

**Capabilities**:
- **Silero VAD** (recommended): State-of-the-art speech detection
- **WebRTC VAD** (fallback): Lightweight alternative
- Configurable confidence threshold
- Minimum speech duration requirement
- Automatic engine selection with fallback

**Configuration**:
```yaml
audio:
  vad_enabled: true
  vad_engine: silero              # or "webrtc"
  vad_threshold: 0.5              # 0.0-1.0
  vad_min_speech_ms: 100          # minimum speech duration
  vad_speech_timeout_ms: 500      # timeout after speech
```

**Usage**:
```python
from aladdin_core.vad import VoiceActivityDetector

vad = VoiceActivityDetector(
    sample_rate=16000,
    engine="silero",
    threshold=0.5,
    min_speech_ms=100,
)

result = vad.detect(audio_chunk)
if result["speech_detected"]:
    print(f"Speech detected (confidence: {result['confidence']:.2f})")
```

### 2. Noise Suppression

**Files**: `aladdin_core/audio_enhancement.py` → `NoiseSuppressionFilter`

**Capabilities**:
- Real-time microphone noise reduction
- Configurable suppression strength (0-3)
- Reduces constant background noise (fan, AC, traffic)
- Preserves speech quality
- Fallback to simple noise gate if library unavailable

**Configuration**:
```yaml
audio:
  noise_suppression_enabled: true
  noise_suppression_strength: 2  # 0=off, 1=light, 2=moderate, 3=aggressive
```

**Usage**:
```python
from aladdin_core.audio_enhancement import NoiseSuppressionFilter

ns = NoiseSuppressionFilter(
    sample_rate=16000,
    strength=2,  # moderate noise suppression
)

clean_audio = ns.process(noisy_audio)
```

### 3. Echo Cancellation

**Files**: `aladdin_core/audio_enhancement.py` → `EchoCanceller`

**Capabilities**:
- Prevents detecting assistant's own TTS output
- Automatically suppresses microphone during playback
- Configurable suppression timeout
- Transparent integration with existing playback
- Ready for future full-duplex support

**Configuration**:
```yaml
audio:
  echo_cancellation_enabled: true
  echo_cancellation_timeout_ms: 100  # buffer after playback stops
```

**Usage**:
```python
from aladdin_core.audio_enhancement import EchoCanceller

ec = EchoCanceller(
    sample_rate=16000,
    timeout_ms=100,
)

# During TTS playback
ec.set_playback_active(True)
# ...
ec.set_playback_active(False)

# Suppress microphone input if needed
if not ec.should_suppress():
    process_microphone_input(audio)
```

### 4. Automatic Silence Detection

**Files**: `aladdin_core/audio_io.py` (enhanced)

**Capabilities**:
- Automatically stop recording after configurable silence
- Detect speech end reliably with VAD
- Configurable minimum speech duration
- Ignore short accidental pauses
- Absolute maximum recording duration

**Configuration**:
```yaml
audio:
  silence_timeout_ms: 800          # stop after 800ms silence
  min_speech_duration_ms: 200      # minimum 200ms to accept
  max_recording_seconds: 30.0      # absolute maximum
```

### 5. Auto Sleep Mode

**Files**: `aladdin_core/state_manager.py` → `StateManager`

**Capabilities**:
- Automatically enters low-power sleep mode after idle timeout
- Wake word detection remains active
- Reduces unnecessary CPU usage
- Automatic timeout reset
- State transition callbacks

**States**:
- `IDLE`: Not listening
- `LISTENING`: Waiting for wake word or input
- `PROCESSING`: Recording/transcription
- `SPEAKING`: TTS playback
- `SLEEPING`: Low-power sleep mode

**Configuration**:
```yaml
audio:
  auto_sleep_enabled: true
  auto_sleep_timeout_ms: 3000  # sleep after 3 seconds idle
```

**Usage**:
```python
from aladdin_core.state_manager import StateManager, AudioState

state_mgr = StateManager(
    auto_sleep_enabled=True,
    auto_sleep_timeout_ms=3000,
)

# Listen for state changes
def on_state_changed(old, new):
    print(f"State: {old.value} → {new.value}")
    if new == AudioState.SLEEPING:
        # Reduce CPU usage
        pass

state_mgr.set_on_state_changed(on_state_changed)
state_mgr.set_state(AudioState.LISTENING)
```

### 6. Auto Resume Listening

**Files**: `aladdin_core/state_manager.py` → `StateManager`

**Capabilities**:
- Automatically resumes listening after STT completes
- Resumes after LLM response completes
- Resumes after TTS playback ends
- Configurable resume delay
- Transparent integration
- Automatic recovery from failures

**Configuration**:
```yaml
audio:
  auto_resume_enabled: true
  auto_resume_delay_ms: 100  # wait 100ms before resuming
```

**Usage**:
```python
state_mgr = StateManager(
    auto_resume_enabled=True,
    auto_resume_delay_ms=100,
)

def on_should_resume():
    print("Resuming listening...")
    start_listening()

state_mgr.set_on_should_resume(on_should_resume)

# After TTS playback completes
state_mgr.resume_listening()
```

---

## Installation

### Core Dependencies

```bash
pip install -r requirements.txt
```

### Optional Audio Enhancement

**Silero VAD** (recommended for best speech detection):
```bash
pip install silero-vad
```

**WebRTC VAD** (lightweight fallback):
```bash
pip install webrtcvad
```

**Noise Suppression**:
```bash
pip install noisereduce
```

---

## Configuration Examples

### Minimal Configuration (Defaults)

```yaml
audio:
  wake_word: aladdin
  continuous_listening: true
  noise_suppression_enabled: true
  auto_resume_enabled: true
```

### High-Quality Voice (More Processing)

```yaml
audio:
  vad_enabled: true
  vad_engine: silero
  vad_threshold: 0.4              # more sensitive
  noise_suppression_enabled: true
  noise_suppression_strength: 3   # aggressive
  echo_cancellation_enabled: true
  auto_resume_enabled: true
```

### Low-Latency Configuration

```yaml
audio:
  audio_chunk_size: 512           # small chunks
  vad_threshold: 0.6              # less sensitive
  noise_suppression_strength: 1   # light
  auto_resume_delay_ms: 50        # quick resume
```

### Battery-Saving Configuration

```yaml
audio:
  auto_sleep_enabled: true
  auto_sleep_timeout_ms: 1000     # sleep sooner
  noise_suppression_strength: 1   # light processing
  vad_enabled: true
  vad_engine: webrtc              # lightweight
```

---

## Architecture

### Signal Flow

```
Microphone
    |
    v
┌─────────────────────────────────────┐
│ Echo Canceller                      │  (suppresses TTS playback)
└──────────┬──────────────────────────┘
           |
           v
┌─────────────────────────────────────┐
│ Noise Suppression Filter            │  (removes background noise)
└──────────┬──────────────────────────┘
           |
           v
┌─────────────────────────────────────┐
│ Voice Activity Detector (VAD)        │  (detects speech)
└──────────┬──────────────────────────┘
           |
           v
┌─────────────────────────────────────┐
│ Wake Word Engine                    │  (detects activation)
└──────────┬──────────────────────────┘
           |
           v
┌─────────────────────────────────────┐
│ State Manager                       │  (auto-sleep, auto-resume)
└──────────┬──────────────────────────┘
           |
           v
      Whisper STT
           |
           v
      Ollama LLM
           |
           v
      Piper TTS
           |
           v
       Speakers
```

---

## Files Modified / Added

### Modified
- **`aladdin_core/config.py`**: Added Voice Core Part 2 configuration options
- **`config.yaml`**: Updated with all new voice settings
- **`requirements.txt`**: Added new dependencies (silero-vad, webrtcvad, noisereduce)

### Added (New)
- **`aladdin_core/vad.py`**: Voice Activity Detection engine
- **`aladdin_core/audio_enhancement.py`**: Noise suppression & echo cancellation
- **`aladdin_core/state_manager.py`**: State management & auto-resume

### Previously Added (Part 1)
- **`aladdin_core/wake_word_engine.py`**: Wake word detection
- **`aladdin_core/audio_stream.py`**: Continuous streaming
- **`aladdin_core/audio_io.py`**: High-level audio interface

---

## Error Handling

All components gracefully handle failures:

### VAD
- Silero unavailable → Falls back to WebRTC
- WebRTC unavailable → Returns default "not detected"
- Model load failures → Logged, continues operation

### Noise Suppression
- Library unavailable → Falls back to simple noise gate
- Processing errors → Passes through unmodified audio

### Echo Cancellation
- Transparent → Only suppresses during/after playback
- No performance impact when disabled

### State Manager
- Safe concurrent access with RLock
- Timer cleanup on shutdown
- Exception handling in callbacks

---

## Performance

### CPU Usage (Per Component)
- **VAD (Silero)**: ~3-5% (depends on model size)
- **VAD (WebRTC)**: ~1-2% (lightweight)
- **Noise Suppression**: ~2-5% (depends on strength)
- **Echo Cancellation**: <0.1% (minimal processing)
- **State Manager**: <0.1% (just timer management)

### Latency
- **VAD Detection**: ~50-100ms
- **Noise Suppression**: ~10-20ms per frame
- **Echo Cancellation**: <1ms
- **Total Overhead**: ~100-200ms from audio input to STT

### Memory
- **Silero VAD Model**: ~60-100MB
- **Audio Buffers**: ~100KB
- **State Manager**: ~10KB
- **Total Additional**: ~60-120MB

---

## Troubleshooting

### VAD Not Detecting Speech
1. Check `vad_enabled: true` in config
2. Lower `vad_threshold` (more sensitive)
3. Verify audio input level
4. Check logs for fallback engine

### Too Much Background Noise
1. Increase `noise_suppression_strength` (1-3)
2. Use Silero VAD for better accuracy
3. Position microphone closer

### Echo Issues
1. Verify `echo_cancellation_enabled: true`
2. Increase `echo_cancellation_timeout_ms` if needed
3. Check speaker/microphone separation

### High CPU Usage
1. Reduce `noise_suppression_strength`
2. Use WebRTC VAD instead of Silero
3. Increase `audio_chunk_size` for processing efficiency
4. Enable `auto_sleep_enabled` for idle periods

---

## Integration with Main Pipeline

All components integrate seamlessly with existing Aladdin code:

```python
# main.py integration
from aladdin_core.vad import VoiceActivityDetector
from aladdin_core.audio_enhancement import NoiseSuppressionFilter, EchoCanceller
from aladdin_core.state_manager import StateManager, AudioState

# Initialize
vad = VoiceActivityDetector(
    sample_rate=cfg.audio.sample_rate,
    engine=cfg.audio.vad_engine,
    threshold=cfg.audio.vad_threshold,
)

ns = NoiseSuppressionFilter(
    sample_rate=cfg.audio.sample_rate,
    strength=cfg.audio.noise_suppression_strength,
)

ec = EchoCanceller(
    sample_rate=cfg.audio.sample_rate,
    timeout_ms=cfg.audio.echo_cancellation_timeout_ms,
)

state_mgr = StateManager(
    auto_sleep_enabled=cfg.audio.auto_sleep_enabled,
    auto_resume_enabled=cfg.audio.auto_resume_enabled,
)

# During recording
audio_chunk = stream.get_audio_chunk()
audio_chunk = ec.process(audio_chunk)  # Echo cancel
audio_chunk = ns.process(audio_chunk)  # Noise suppress
result = vad.detect(audio_chunk)       # Detect speech

# State transitions
state_mgr.set_state(AudioState.PROCESSING)
# ... process audio ...
state_mgr.resume_listening()  # Auto-resume after completion
```

---

## Future Enhancements

- **Advanced Echo Cancellation**: Spectral subtraction, Wiener filtering
- **Acoustic Scene Detection**: Identify environment (quiet, noisy, crowded)
- **Speaker Identification**: Recognize different speakers
- **Language Detection**: Auto-detect language from audio
- **Full-Duplex Support**: Simultaneous listening and speaking
- **Noise Fingerprinting**: Learn environment-specific noise patterns

---

## References

- **Silero VAD**: https://github.com/snakers4/silero-vad
- **WebRTC VAD**: https://github.com/wiseman/py-webrtcvad
- **NoiseReduce**: https://github.com/timsainb/noisereduce
- **sounddevice**: https://github.com/spatialaudio/python-sounddevice

---

## License

Same as Aladdin project (MIT License)
