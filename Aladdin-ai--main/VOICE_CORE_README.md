# Voice Core Implementation

## Overview

This document describes the Voice Core features implemented in Aladdin, including wake word detection, continuous listening, and streaming audio capture.

## Features Implemented

### 1. Dedicated Wake Word Engine

**File**: `aladdin_core/wake_word_engine.py`

- **OpenWakeWord Integration**: Uses OpenWakeWord library for accurate wake word detection
- **Fallback Support**: Can work without OpenWakeWord installed (graceful degradation)
- **Configurable Wake Words**: Supports "aladdin", "alexa", "google", and more
- **Confidence Threshold**: Adjustable threshold to filter false positives
- **Cooldown Period**: Prevents repeated triggers within a cooldown window
- **Extensible Design**: Easy to add new wake words or detection methods

### 2. Continuous Microphone Listening

**File**: `aladdin_core/audio_stream.py` → `AudioStreamManager`

- **Background Thread**: Non-blocking continuous audio capture
- **Automatic Error Recovery**: Exponential backoff reconnection logic
- **Device Enumeration**: Support for selecting specific audio devices
- **Low Latency**: ~32ms chunk processing
- **Thread-Safe**: Safe to call from multiple threads

### 3. Streaming Audio Capture

**Features**:
- Continuous audio chunks in configurable sizes (default: 512 samples @ 16kHz = 32ms)
- Queue-based streaming suitable for streaming STT/Whisper integration
- Audio buffering for wake word detection
- Mono audio normalization
- Frame-by-frame callbacks

### 4. False Trigger Filtering

- **Confidence Threshold**: Only detects if confidence > threshold (default: 0.5)
- **Minimum Audio Length**: Ignores very short clips
- **Cooldown Period**: No duplicate triggers within cooldown window (default: 1.0s)
- **Minimum Audio Duration**: Requires at least 0.5s of audio before checking

### 5. Error Handling & Recovery

- **Device Errors**: Gracefully handles disconnected/missing microphones
- **Stream Failures**: Automatic reconnection with exponential backoff
- **Max Reconnection Attempts**: Limits retry attempts to prevent infinite loops
- **Logging**: Comprehensive error logging for debugging
- **Clean Shutdown**: Proper cleanup of resources

## Configuration

Edit `config.yaml` under the `audio:` section:

```yaml
audio:
  sample_rate: 16000              # Audio sample rate (Hz)
  audio_chunk_size: 512           # Samples per streaming chunk (~32ms @ 16kHz)
  
  # Wake word settings
  wake_word: aladdin              # Wake phrase
  wake_word_enabled: true         # Enable/disable detection
  wake_word_model: microphone     # Model name or path
  wake_word_threshold: 0.5        # Confidence threshold (0.0-1.0)
  wake_word_cooldown: 1.0         # Cooldown between detections (seconds)
  
  # Continuous listening
  continuous_listening: true      # Keep mic open continuously
```

## Installation

### Prerequisites

```bash
pip install -r requirements.txt
```

### OpenWakeWord Setup

OpenWakeWord is optional but recommended for accurate wake word detection:

```bash
pip install openwakeword
```

**Note**: If OpenWakeWord is not installed, the system will gracefully fall back to limited wake word support.

## Usage

### Basic Voice Loop

```bash
python main.py
```

This starts the assistant with continuous listening and wake word detection:
1. Assistant idles and waits for "Aladdin" wake word
2. When detected, it listens for user input
3. Processes and responds
4. Returns to idle state

### Text-Only Mode

```bash
python main.py --text
```

Disables audio I/O and mic.

### Disable Wake Word

```bash
python main.py --no-wake-word
```

Always listening mode without wake word requirement.

### Daemon Mode

```bash
python main.py --daemon
```

Background service mode with continuous wake word listening.

## Architecture

### Component Diagram

```
┌─────────────────────────────────────────────────────┐
│  AudioIO (high-level interface)                     │
│  - record_until_silence()                           │
│  - play()                                           │
│  - wait_for_wake_word()                             │
│  - start_listening() / stop_listening()             │
└──────────────────────┬────────────────────────────┘
                       │
        ┌──────────────┴──────────────┐
        │                             │
        v                             v
┌──────────────────┐    ┌──────────────────────────┐
│ AudioStreamMgr   │    │  WakeWordEngine          │
│                  │    │                          │
│ - Recording loop │    │ - OpenWakeWord wrapper   │
│ - Queuing audio  │    │ - Confidence filtering   │
│ - Error recovery │    │ - Cooldown logic         │
│ - Callbacks      │    │ - Fallback support       │
└──────────────────┘    └──────────────────────────┘
        │
        v
   sounddevice library
   (OS audio I/O)
```

### Key Classes

#### `WakeWordEngine`
- Initializes OpenWakeWord model
- Detects wake words in audio chunks
- Manages confidence thresholds and cooldown
- Logs detection results

**Methods**:
```python
engine = WakeWordEngine(
    wake_word="aladdin",
    threshold=0.5,
    cooldown=1.0,
    sample_rate=16000,
)

result = engine.detect(audio_array)
# result = {"detected": bool, "confidence": float, "word": str}
```

#### `AudioStreamManager`
- Manages continuous microphone input in background thread
- Detects wake words in real-time
- Provides audio chunks via queue or callback
- Automatic error recovery with exponential backoff

**Methods**:
```python
mgr = AudioStreamManager(
    sample_rate=16000,
    chunk_size=512,
    enable_wake_word=True,
    wake_word_config={"wake_word": "aladdin", "threshold": 0.5},
)

mgr.start()  # Start background thread
mgr.set_on_audio_chunk(lambda chunk: print(f"Got {len(chunk)} samples"))
mgr.set_on_wake_word_detected(lambda result: print(f"Detected: {result}"))

chunk = mgr.get_audio_chunk(timeout=1.0)  # Blocking get
mgr.stop()  # Stop and cleanup
```

#### `AudioIO`
- High-level audio I/O interface
- Wraps AudioStreamManager
- Provides recording, playback, and wake word waiting
- Compatible with existing TTS/STT pipeline

**Methods**:
```python
audio = AudioIO(cfg)
audio.start_listening()  # Background wake word listening

# Wait for wake word
audio.wait_for_wake_word(
    timeout=30.0,
    on_detected=lambda: print("Wake word detected!")
)

# Record until silence
wav_path = audio.record_until_silence()

# Play audio file
audio.play(wav_path)

# Cleanup
audio.shutdown()
```

## Integration with Main Pipeline

### main.py Changes

The `main.py` entry point has been updated to:

1. Initialize AudioIO with wake word configuration
2. Call `audio.start_listening()` at startup
3. Call `audio.wait_for_wake_word()` in the main loop
4. Maintain backward compatibility with `--no-wake-word` flag

### Example Flow

```python
async def run_loop(self):
    """Continuous conversation loop."""
    self.audio.start_listening()  # Start background listening
    
    while self._running:
        if self.use_wake_word:
            # Wait for wake word (non-blocking in background)
            self.audio.wait_for_wake_word(
                timeout=None,
                on_detected=lambda: print("✅ Wake word detected!")
            )
        
        # Record user input
        user_text = self.listen()
        
        # Process and respond
        reply = self.process(user_text)
        self.say(reply)
```

## Performance Considerations

### CPU Usage
- **OpenWakeWord**: ~5-10% CPU overhead on typical systems
- **Audio Capture**: Minimal overhead with background thread
- **Streaming**: ~1KB/s audio data rate @ 16kHz mono

### Latency
- **Wake Word Detection**: ~200-500ms (depends on model and audio buffer)
- **Audio Chunk Processing**: ~32ms per chunk
- **Total Activation Latency**: ~500ms-1s from wake word end to response

### Memory
- **OpenWakeWord Model**: ~50-100MB loaded in memory
- **Audio Buffers**: ~64KB for 2-second buffer
- **Total Additional Memory**: ~50-150MB

## Troubleshooting

### Wake Word Not Detected

1. **Check OpenWakeWord installed**: `pip install openwakeword`
2. **Verify microphone working**: `python -m sounddevice` to list devices
3. **Adjust threshold**: Lower value = more sensitive but more false positives
4. **Check audio level**: Speak clearly at normal volume
5. **Review logs**: Set `logging.level: DEBUG` in config.yaml

### Microphone Errors

1. **Check device availability**: `python -c "import sounddevice; print(sounddevice.query_devices())"`
2. **Specify device**: Add `device` parameter in code or set via OS
3. **Check permissions**: Ensure microphone permissions granted
4. **Try default device**: Leave device=None in config

### High CPU Usage

1. **Disable wake word**: Set `wake_word_enabled: false`
2. **Lower sample rate**: Not recommended, may affect quality
3. **Increase chunk size**: Trade latency for CPU efficiency
4. **Check for errors**: See logs for repeated reconnection attempts

## Future Extensions

### Voice Activity Detection (VAD)
- Integrated with Silero VAD or similar
- Detect when user stops speaking (more accurate than silence detection)

### Streaming STT
- Feed audio chunks directly to streaming Whisper
- Lower latency transcription
- Partial results support

### Barge-In Support
- User can interrupt TTS response
- Integrated with interrupt_enabled config
- Stop playback on voice detection

### Multi-Wake-Word Support
- Add multiple wake words
- Different actions per wake word
- Dynamic wake word switching

### Audio Enhancement
- Noise suppression
- Echo cancellation
- Automatic gain control

## Development Notes

### Adding New Wake Words

```python
# In wake_word_engine.py
_supported_words = [
    "aladdin",
    "alexa",
    "custom_phrase",  # Add your phrase
]

# OpenWakeWord will auto-load model if available
```

### Adding Custom Audio Processing

```python
# In AudioStreamManager
mgr.set_on_audio_chunk(my_callback)

def my_callback(audio_chunk: np.ndarray):
    # audio_chunk is float32 mono array
    # Can feed to VAD, enhancement, streaming STT, etc.
    pass
```

### Testing

```bash
# Test wake word detection
python -c "
from aladdin_core.wake_word_engine import WakeWordEngine
engine = WakeWordEngine('aladdin', threshold=0.5)
print('Supported words:', engine.get_supported_words())
"

# Test audio stream
python -c "
from aladdin_core.audio_stream import AudioStreamManager
mgr = AudioStreamManager(enable_wake_word=True)
mgr.start()
import time; time.sleep(5)
mgr.stop()
print('Audio stream test completed')
"
```

## References

- **OpenWakeWord**: https://github.com/dscripka/openWakeWord
- **sounddevice**: https://github.com/spatialaudio/python-sounddevice
- **soundfile**: https://github.com/bastibe/soundfile
- **Whisper**: https://github.com/openai/whisper

## License

Same as Aladdin project (MIT License)
