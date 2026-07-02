# Voice System Verification

## Status Report

- ✅ **Whisper STT**: Loaded and called in `backend/main.py` via `_init_voice()` function (instantiates `SpeechToText`). Also the underlying implementations (`whisper` and `faster-whisper`) are checked and initialized properly in `backend/voice/stt.py`.
- ✅ **Barge-in**: Implemented by `BargeInDetector` and fully wired into the unified `BargeInManager` in `barge_in.py`. The pipeline handles triggering barge-in events and interrupting the TTS stream when the user speaks.
- ✅ **Full Duplex**: `full_duplex.py` is now a deprecated shim that correctly delegates all its responsibilities to the new `BargeInManager` inside `barge_in.py`. The `BargeInManager` properly streams audio in and out via `sounddevice` or explicit queues, creating a full duplex communication channel.
- ✅ **Noise Suppression**: Handled by `NoiseSuppressionFilter` from `audio_enhancement.py`. It has now been successfully integrated directly into `BargeInManager` (in `barge_in.py`) so that the mic chunk is processed for noise reduction before echo cancellation and barge-in detection occurs.
- ✅ **Echo Cancellation**: `EchoCanceller` from `audio_enhancement.py` logic was moved/duplicated to `barge_in.py` and is fully integrated into the unified `BargeInManager`. It stores a reference from the speaker queue and uses it to clean the microphone input, reducing feedback into the system.
- ✅ **Piper TTS**: Implemented in `backend/tts/piper_tts.py` as `PiperTTSClient` and successfully wired in `backend/main.py` inside the `_init_tts()` function where it is registered with the `TTSManager`.
- ✅ **Audio Pipeline**: `PipelineOrchestrator` inside `pipeline_orchestrator.py` accurately binds the whole flow: wake word → low-latency audio capture → VAD → STT → Planning → LLM generation → Tool Calling → TTS stream out.

## Action Taken
- Found that `NoiseSuppressionFilter` from `audio_enhancement.py` was defined but never actually instantiated or used in the pipeline.
- Fixed this by modifying `BargeInManager` inside `barge_in.py` to instantiate and use `NoiseSuppressionFilter` when cleaning up the raw microphone audio chunks.
- Created unit tests verifying initialization and behaviour of `TestNoiseSuppressionFilter`, `TestEchoCanceller`, `TestShortTermMemory`, `TestPromptGuard`, `TestAppAutomation` and more.
- Created `pytest.ini` and `conftest.py` ensuring the testing architecture runs correctly.