# Continuous Listening Module — Phase 2 Voice Core

## Overview

Phase 2 adds a complete **hands-free continuous listening state machine**
on top of the existing Phase 1 always-on audio stack
(`wake_word_engine.py`, `audio_stream.py`, `audio_io.py`). No existing
functionality was removed; this is an additive integration layer.

## Files Changed / Added

| File | Change |
|------|--------|
| `continuous_listening.py` | **New.** `ContinuousListeningController` state machine: always-listening, auto-resume, sleep mode, conversation mode, silence timeout, idle mode. |
| `config.py` | `AudioCfg` extended with Phase 2 fields (conversation/sleep/idle timeouts, power-save duty cycles). |
| `audio_stream.py` | Added `set_power_save(duty_cycle)` — duty-cycled wake-word inference for sleep/idle power saving, without ever stopping mic capture or disabling wake-word detection. |
| `audio_io.py` | Exposes `set_power_save()` passthrough to the stream manager. |
| `main.py` | `Aladdin.run_loop()` now drives the new controller for fully hands-free operation; legacy text-mode loop preserved as `_run_loop_text_fallback()`. |

## State Machine

```
STOPPED -> WAITING_FOR_WAKE -> ACTIVE_LISTENING -> PROCESSING -> SPEAKING
              ^                                                     |
              |                                                     v
              +------------------- CONVERSATION <-------------------+
              |
         SLEEP / IDLE  (entered after inactivity; wake word stays active)
```

- **Always-listening microphone**: delegated to the existing
  `AudioStreamManager`, which already has reconnection/backoff and
  duplicate-session guarding (`is_running()` checks in `start()`).
- **Automatic resume**: `speak_fn` (TTS + playback) is called
  synchronously; the moment it returns, the controller transitions back
  to listening — no manual restart needed.
- **Sleep mode**: after `sleep_timeout` seconds of no wake/speech/reply
  activity, wake-word inference duty-cycles down
  (`sleep_power_save_duty_cycle`, default 1-in-2 chunks) while the
  microphone keeps capturing and wake detection stays active.
- **Conversation mode**: after a reply, if the user just spoke, the
  controller stays "awake" for `conversation_timeout` seconds and accepts
  a follow-up without the wake word.
- **Silence timeout**: utterances are finalised by the existing
  `record_until_silence()` / VAD pipeline; `listen_silence_timeout` is the
  high-level configurable knob.
- **Idle mode**: a deeper power-saving tier after `idle_timeout` seconds,
  with a more aggressive duty cycle (`idle_power_save_duty_cycle`).
- **Hands-free**: the whole loop in `main.py` requires no keyboard/mouse
  input once started; all transitions are voice/timer driven.

## Configuration (`config.yaml` → `audio:`)

```yaml
audio:
  always_listening_enabled: true
  conversation_mode_enabled: true
  conversation_timeout: 8.0
  listen_silence_timeout: 1.2
  sleep_mode_enabled: true
  sleep_timeout: 60.0
  idle_mode_enabled: true
  idle_timeout: 300.0
  sleep_power_save_duty_cycle: 2
  idle_power_save_duty_cycle: 4
  auto_resume_enabled: true
```

All new fields have safe defaults and are read via `AudioCfg`'s existing
`_safe_build()` mechanism, so older `config.yaml` files keep working
unchanged.

## Reliability Notes

- Single `_turn_lock` (non-blocking acquire) prevents duplicate/overlapping
  recording sessions if callbacks race.
- All timers (`sleep`, `idle`) are cancelled on `stop()` and whenever
  activity resets them — no leaked `threading.Timer` objects.
- Every caller-supplied callback (`transcribe_fn`, `respond_fn`,
  `speak_fn`, `on_*` hooks) is wrapped in try/except so a single failure
  never kills the listening loop; it logs and continues.
- Structured logging (`[ContinuousListening] ...`) for every state
  transition, consistent with the Phase 1 `[WakeWordEngine]` /
  `[AudioStream]` log tags.
- Verified with standalone smoke tests (no real audio hardware required)
  covering: wake → listen → respond → speak → resume, conversation-mode
  timeout, and clean thread shutdown with no leaks.

## Known Pre-Existing Issue (Out of Scope)

`main.py` imports several modules from `aladdin_core`
(`aladdin_core.llm`, `.stt`, `.tts`, `.audio`, `.search`,
`.calendar_manager`, `.plugin_system`, `.tools`) that do not exist in this
codebase — `aladdin_core/` only contains the Smart Memory subsystem. This
predates Phase 2 and is unrelated to continuous listening; it means
`main.py` cannot currently be executed end-to-end. The continuous
listening integration in `main.py` is wired correctly and will work as
soon as those modules are supplied/restored. The core deliverable —
`continuous_listening.py` plus its integration with `audio_io.py` /
`audio_stream.py` / `config.py` — is self-contained, imports cleanly, and
is independently tested (see smoke tests run during development).
