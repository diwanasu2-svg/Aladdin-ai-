#!/usr/bin/env python3
"""
Aladdin — AI Personal Voice Assistant
======================================
Single entry point. Run with:

    python main.py              # full voice loop (mic + speakers)
    python main.py --text       # text-only mode
    python main.py --once       # single turn and exit
    python main.py --daemon     # background service mode
    python main.py --setup      # interactive first-run setup

Pipeline:
    Microphone → Whisper (STT) → Memory → Search → Ollama (LLM) → Piper (TTS) → Speakers
"""

from __future__ import annotations

import argparse
import logging
import os
import sys
import threading
import time
from pathlib import Path

# ---------------------------------------------------------------------------
# Make vendored packages importable without editing their internals.
# ---------------------------------------------------------------------------
ROOT = Path(__file__).resolve().parent
VENDOR = ROOT / "vendor"
for sub in ("whisper", "piper/src/python_run", "open_webui/backend"):
    p = VENDOR / sub
    if p.exists():
        sys.path.insert(0, str(p))
# Also make aladdin_core importable when run from anywhere
sys.path.insert(0, str(ROOT))

from aladdin_core.config import AladdinConfig  # noqa: E402
from aladdin_core.logger import setup_logging  # noqa: E402
from aladdin_core.llm import OllamaClient  # noqa: E402
from aladdin_core.stt import WhisperTranscriber  # noqa: E402
from aladdin_core.tts import PiperSynthesizer  # noqa: E402
from aladdin_core.memory import ConversationMemory  # noqa: E402
from aladdin_core.audio import AudioIO  # noqa: E402
from aladdin_core.search import InternetSearch, needs_search  # noqa: E402
from aladdin_core.calendar_manager import CalendarManager, ReminderManager  # noqa: E402
from aladdin_core.plugin_system import PluginManager  # noqa: E402
from aladdin_core.tools import register_all_tools  # noqa: E402

# Phase 9+10 — Backend tool registry (telegram, discord, maps, phone, sms, etc.)
try:
    from backend.tools import get_tool_registry as _get_backend_tools
    _BACKEND_TOOLS_AVAILABLE = True
except ImportError:
    _BACKEND_TOOLS_AVAILABLE = False

# Phase 10 — Computer control tools (mouse, keyboard, screen, app automation)
try:

# Phase 11 — Intelligence / Proactive AI module
try:
    from backend.intelligence import (
        ReminderService, HabitPredictor, BriefingGenerator,
        NewsAggregator, CalendarOptimizer, ContextManager
    )
    _INTELLIGENCE_AVAILABLE = True
except ImportError as _exc:
    _INTELLIGENCE_AVAILABLE = False
    from backend.computer_control import get_registry as _get_cc_registry
    _CC_TOOLS_AVAILABLE = True
except ImportError:
    _CC_TOOLS_AVAILABLE = False
from continuous_listening import (  # noqa: E402
    ContinuousListeningConfig,
    ContinuousListeningController,
    ListenState,
)

# ── Phases 3,5,9,10 imports ───────────────────────────────────────────────────
try:
    from pipeline_orchestrator import PipelineOrchestrator as _PO

    _ORCHESTRATOR_AVAILABLE = True
except ImportError:
    _ORCHESTRATOR_AVAILABLE = False

try:
    from messaging import MessagingManager as _MM

    _MESSAGING_AVAILABLE = True
except ImportError:
    _MESSAGING_AVAILABLE = False

try:
    from performance_optimizer import PerformanceMonitor as _PM, warm_up_async as _WUA

    _PERF_AVAILABLE = True
except ImportError:
    _PERF_AVAILABLE = False

try:
    from reliability import (
        CrashHandler as _CH,
        setup_rotating_logs as _SRL,
        validate_dependencies as _VD,
    )

    _RELIABILITY_AVAILABLE = True
except ImportError:
    _RELIABILITY_AVAILABLE = False

try:
    import reasoning_ext  # noqa: F401 — patches ReasoningEngine in place
except ImportError:
    pass


try:
    from low_latency import LowLatencyPipeline as _LLP

    _LOW_LATENCY_AVAILABLE = True
except ImportError:
    _LOW_LATENCY_AVAILABLE = False

try:
    from barge_in import BargeInManager as _BIM

    _BARGE_IN_AVAILABLE = True
except ImportError:
    _BARGE_IN_AVAILABLE = False

ASSISTANT_NAME = "Aladdin"
log = logging.getLogger(ASSISTANT_NAME.lower())


# ---------------------------------------------------------------------------
# Argument parser
# ---------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="aladdin",
        description=f"{ASSISTANT_NAME} — AI Personal Voice Assistant",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python main.py                 full voice assistant (mic + TTS)
  python main.py --text          text-only chat mode
  python main.py --text --once   single Q&A and exit
  python main.py --daemon        run as background service
  python main.py --setup         interactive first-run configuration
""",
    )
    p.add_argument(
        "--config",
        default=str(ROOT / "config.yaml"),
        help="Path to YAML config (default: config.yaml)",
    )
    p.add_argument(
        "--text", action="store_true", help="Text-only mode — no microphone or speakers"
    )
    p.add_argument(
        "--once", action="store_true", help="Process a single turn then exit"
    )
    p.add_argument(
        "--daemon",
        action="store_true",
        help="Run as a background service (no interactive prompt)",
    )
    p.add_argument(
        "--setup", action="store_true", help="Run interactive first-run setup wizard"
    )
    p.add_argument(
        "--log-level",
        default=None,
        help="Override log level (DEBUG|INFO|WARNING|ERROR)",
    )
    p.add_argument(
        "--no-wake-word",
        action="store_true",
        help="Disable wake-word detection (always listening)",
    )
    p.add_argument("--version", action="version", version="Aladdin 2.0.0")
    return p


# ---------------------------------------------------------------------------
# Setup wizard
# ---------------------------------------------------------------------------


def run_setup_wizard(cfg_path: str) -> None:
    """Interactive first-run configuration wizard."""
    log.info(f"\n{'='*60}")

    log.info(f"  Welcome to {ASSISTANT_NAME} Setup Wizard")

    log.info(f"{'='*60}\n")


    import yaml

    cfg_file = Path(cfg_path)
    data = {}
    if cfg_file.exists():
        data = yaml.safe_load(cfg_file.read_text()) or {}

    def ask(prompt: str, default: str) -> str:
        val = input(f"  {prompt} [{default}]: ").strip()
        return val if val else default

    log.info("1. Ollama LLM Settings")

    host = ask(
        "Ollama host", data.get("ollama", {}).get("host", "http://localhost:11434")
    )
    model = ask("Model name", data.get("ollama", {}).get("model", "llama3"))
    data.setdefault("ollama", {})
    data["ollama"]["host"] = host
    data["ollama"]["model"] = model

    log.info("\n2. Voice Settings")

    model_path = ask(
        "Piper voice model path",
        data.get("piper", {}).get("model_path", "voices/en_US-amy-medium.onnx"),
    )
    data.setdefault("piper", {})
    data["piper"]["model_path"] = model_path

    log.info("\n3. Whisper Settings")

    whisper_model = ask(
        "Whisper model (tiny|base|small|medium|large)",
        data.get("whisper", {}).get("model", "base"),
    )
    data.setdefault("whisper", {})
    data["whisper"]["model"] = whisper_model

    log.info("\n4. Wake Word")

    wake_word = ask("Wake word", data.get("audio", {}).get("wake_word", "aladdin"))
    data.setdefault("audio", {})
    data["audio"]["wake_word"] = wake_word

    cfg_file.write_text(yaml.dump(data, default_flow_style=False))
    log.info(f"\n  Config saved to {cfg_file}")

    log.info("  Run 'python main.py' to start Aladdin!\n")



# ---------------------------------------------------------------------------
# Core assistant class
# ---------------------------------------------------------------------------


class Aladdin:
    """Main assistant orchestrator."""

    def __init__(
        self, cfg: AladdinConfig, text_only: bool = False, no_wake_word: bool = False
    ):
        self.cfg = cfg
        self.text_only = text_only
        self.use_wake_word = (
            cfg.audio.wake_word_enabled and not no_wake_word and not text_only
        )
        self._running = False
        self._speaking = threading.Event()

        log.info("Initialising %s v2.0 …", ASSISTANT_NAME)

        # Data directory
        Path("data").mkdir(exist_ok=True)
        Path("logs").mkdir(exist_ok=True)

        # Core modules
        self.memory = ConversationMemory(cfg.memory)
        self.search = InternetSearch(cfg.search)
        self.calendar = CalendarManager(cfg.calendar) if cfg.calendar.enabled else None
        self.reminder = (
            ReminderManager(
                cfg.reminder,
                on_trigger=self._on_reminder,
            )
            if cfg.reminder.enabled
            else None
        )

        # System prompt with memory context
        context = self.memory.build_context_prompt()
        system_prompt = cfg.system_prompt.format(name=ASSISTANT_NAME)
        if context:
            system_prompt = f"{system_prompt}\n\n{context}"

        self.llm = OllamaClient(cfg.ollama, system_prompt=system_prompt)
        register_all_tools(
            self.llm, self.memory, self.search, self.calendar, self.reminder
        )

        # STT / TTS
        self.stt = WhisperTranscriber(cfg.whisper)
        self.tts = PiperSynthesizer(cfg.piper)

        # Audio I/O
        self.audio = None if text_only else AudioIO(cfg.audio)

        # Hands-free continuous listening orchestrator (Phase 2 Voice Core)
        self.continuous_listening: Optional[ContinuousListeningController] = None
        if self.audio is not None:
            self.continuous_listening = ContinuousListeningController(
                audio=self.audio,
                config=ContinuousListeningConfig.from_audio_cfg(cfg.audio),
                transcribe_fn=self.stt.transcribe,
                respond_fn=self._handsfree_respond,
                speak_fn=self.say,
                on_state_change=self._on_listen_state_change,
                on_wake=lambda: log.info("  ✅  Wake word detected!"),
                on_user_text=lambda t: log.info(f"you > {t}          "),
            )

        # Plugin system
        self.plugins = PluginManager(cfg.plugins)
        self.plugins.set_context(
            llm=self.llm,
            memory=self.memory,
            search=self.search,
            calendar=self.calendar,
            reminder=self.reminder,
        )
        self.plugins.load_all()

        # FIX 2 — Low-Latency Pipeline
        self._low_latency_pipeline = None
        if _LOW_LATENCY_AVAILABLE and not self.text_only:
            try:
                self._low_latency_pipeline = _LLP(
                    sample_rate=getattr(cfg.audio, "sample_rate", 16000),
                    target_latency_ms=500,
                    enable_async=True,
                )
                self._low_latency_pipeline.start_async_processing()
                log.info("Low-latency audio pipeline started (target 500 ms)")
            except Exception as _exc:
                log.warning("Low-latency pipeline init failed: %s", _exc)

        # FIX 2+3 — Unified Barge-In Manager (replaces FullDuplexAudioManager)
        self.barge_in = None
        if _BARGE_IN_AVAILABLE and not self.text_only and self.audio is not None:
            try:
                self.barge_in = _BIM(
                    sample_rate=getattr(cfg.audio, "sample_rate", 16000),
                    enabled=getattr(cfg.audio, "interrupt_enabled", True),
                )
                if hasattr(self.tts, "interrupt"):
                    self.barge_in.set_tts_interrupt_fn(self.tts.interrupt)
                self.barge_in.set_on_barge_in(
                    lambda: log.info("Barge-in: TTS interrupted, resuming listen")
                )
                self.barge_in.start()
                log.info("BargeInManager started (unified barge-in, echo cancel)")
            except Exception as _exc:
                log.warning("BargeInManager init failed: %s", _exc)

        # Start reminder checker
        if self.reminder:
            self.reminder.start()

        log.info(
            "%s initialised. LLM available: %s", ASSISTANT_NAME, self.llm.is_available()
        )

    # ------------------------------------------------------------------
    # Public interface
    # ------------------------------------------------------------------

    def say(self, text: str) -> None:
        """Speak text aloud and/or print it."""
        log.info(f"{ASSISTANT_NAME.lower()} > {text}\n")

        if not self.text_only and self.audio and self.tts:
            self._speaking.set()
            try:
                wav = self.tts.synthesize(text)
                self.audio.play(wav)
                try:
                    os.unlink(wav)
                except OSError:
                    pass
            except Exception as exc:
                log.error("TTS/playback error: %s", exc)
            finally:
                self._speaking.clear()

    def listen(self) -> str:
        """Capture user speech and return transcribed text."""
        if self.text_only:
            try:
                return input("you > ").strip()
            except EOFError:
                return ""

        # If currently speaking and interrupt enabled, stop
        if self.audio and self.audio.is_playing and self.cfg.audio.interrupt_enabled:
            log.info("User interrupted Aladdin.")
            self.audio.stop_playback()
            time.sleep(0.2)

        log.info("Listening…")
        log.info("  🎙  Listening…", end="\r", flush=True)

        wav_path = self.audio.record_until_silence()
        log.info("  🔄  Processing…", end="\r", flush=True)

        text = self.stt.transcribe(wav_path)
        try:
            os.unlink(wav_path)
        except OSError:
            pass
        if text:
            log.info(f"you > {text}          ")

        return text.strip()

    def process(self, user_text: str) -> str:
        """Run the full pipeline: plugins → search → LLM → reply."""
        # Plugin short-circuit
        plugin_reply = self.plugins.process_input(user_text)
        if plugin_reply is not None:
            return plugin_reply

        # Context-aware history
        history = self.memory.recent(self.cfg.memory.window)

        # Auto web search augmentation
        search_context = ""
        if needs_search(user_text) and self.cfg.search.enabled:
            log.info("Searching web for: %s", user_text[:60])
            search_answer = self.search.answer(user_text)
            if search_answer:
                search_context = f"\n\n[Web search result: {search_answer}]"
                log.debug("Search result: %s", search_answer[:100])

        augmented = user_text + search_context if search_context else user_text
        reply = self.llm.chat(augmented, history=history)

        # Post-process through plugins
        reply = self.plugins.process_reply(user_text, reply)

        # Store in memory
        self.memory.append(user_text, reply)
        self.memory.summarize_old()

        return reply

    def _handsfree_respond(self, user_text: str) -> str:
        """Response callback used by ContinuousListeningController.

        Handles exit phrases and built-in memory queries the same way the
        legacy run_loop did, then falls back to process().
        """
        lowered = user_text.lower()

        if any(
            w in lowered
            for w in ["goodbye", "bye aladdin", "stop assistant", "shut down"]
        ):
            if self.continuous_listening:
                threading.Thread(
                    target=self.continuous_listening.stop, daemon=True
                ).start()
            self._running = False
            return "Goodbye! Have a great day."

        if "what do you know about me" in lowered:
            facts = self.memory.all_facts()
            if facts:
                summary = ", ".join(f"{f['key']}: {f['value']}" for f in facts[:5])
                return f"Here's what I remember about you: {summary}."
            return "I don't have any saved facts about you yet."

        return self.process(user_text)

    def _on_listen_state_change(self, old: "ListenState", new: "ListenState") -> None:
        log.info("Listening state: %s -> %s", old.name, new.name)
        icons = {
            ListenState.WAITING_FOR_WAKE: f"  💤  Say '{self.cfg.audio.wake_word}' to wake me…",
            ListenState.ACTIVE_LISTENING: "  🎙  Listening…",
            ListenState.PROCESSING: "  🔄  Processing…",
            ListenState.SPEAKING: "  🔊  Speaking…",
            ListenState.CONVERSATION: "  💬  Listening for follow-up…",
            ListenState.SLEEP: "  😴  Sleeping (say the wake word to resume)…",
            ListenState.IDLE: "  🌙  Idle (power-saving, wake word still active)…",
        }
        msg = icons.get(new)
        if msg:
            print(
                msg, end="\r" if new != ListenState.CONVERSATION else "\n", flush=True
            )

    # ------------------------------------------------------------------
    # Main loops
    # ------------------------------------------------------------------

    def run_once(self) -> None:
        """Single conversation turn."""
        user_text = self.listen()
        if not user_text:
            self.say("I didn't catch that. Could you repeat?")
            return
        reply = self.process(user_text)
        self.say(reply)

    def run_loop(self) -> None:
        """Continuous, fully hands-free conversation loop.

        Hands-free operation requires no keyboard, mouse, button presses,
        or manual microphone/conversation restarts — everything is driven
        by ``ContinuousListeningController``: always-on mic, wake word,
        automatic resume after each reply, conversation mode for
        follow-ups, and sleep/idle power saving during inactivity.
        """
        self._running = True

        if self.text_only or self.continuous_listening is None:
            return self._run_loop_text_fallback()

        self.say(f"Hello! I'm {ASSISTANT_NAME}, your AI assistant. How can I help?")

        try:
            self.continuous_listening.start()
            while self._running and self.continuous_listening.is_running():
                time.sleep(0.25)
        except KeyboardInterrupt:
            pass
        finally:
            self._running = False
            self.shutdown()

    def _run_loop_text_fallback(self) -> None:
        """Text-mode (or no-audio) conversation loop — manual turn-taking."""
        self.say(f"Hello! I'm {ASSISTANT_NAME}, your AI assistant. How can I help?")
        try:
            while self._running:
                user_text = self.listen()
                if not user_text:
                    continue
                if any(
                    w in user_text.lower()
                    for w in ["goodbye", "bye aladdin", "stop assistant", "shut down"]
                ):
                    self.say("Goodbye! Have a great day.")
                    break
                reply = self._handsfree_respond(user_text)
                self.say(reply)
        except KeyboardInterrupt:
            pass
        finally:
            self._running = False
            self.shutdown()

    def run_daemon(self) -> None:
        """Background service — always listening via wake word."""
        log.info("Starting %s in daemon mode.", ASSISTANT_NAME)
        self.use_wake_word = True
        log.info(f"  {ASSISTANT_NAME} running as background service.")

        log.info(f"  Say '{self.cfg.audio.wake_word}' to activate.")

        log.info("  Press Ctrl-C to stop.\n")

        self.run_loop()

    # ------------------------------------------------------------------
    # Callbacks
    # ------------------------------------------------------------------

    def _on_reminder(self, message: str) -> None:
        """Called when a reminder triggers."""
        log.info("Reminder: %s", message)
        self.say(f"Reminder: {message}")

    # ------------------------------------------------------------------
    # Cleanup
    # ------------------------------------------------------------------

    def shutdown(self) -> None:
        log.info("%s shutting down.", ASSISTANT_NAME)
        if self.continuous_listening:
            self.continuous_listening.stop()
        # FIX 3 — stop unified barge-in manager
        if getattr(self, "barge_in", None) is not None:
            try:
                self.barge_in.stop()
            except Exception:
                pass
        if self.audio:
            self.audio.shutdown()
        self.plugins.shutdown()
        if self.reminder:
            self.reminder.stop()
        stats = self.memory.stats()
        log.info("Memory stats: %s", stats)
        log.info(f"\n{ASSISTANT_NAME} stopped. Goodbye.")



# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def main() -> None:
    args = build_parser().parse_args()

    # Load config first so we have logging settings
    cfg = AladdinConfig.load(args.config)

    # Override log level from CLI if provided
    if args.log_level:
        cfg.logging.level = args.log_level.upper()

    setup_logging(cfg.logging)

    # Setup wizard
    if args.setup:
        run_setup_wizard(args.config)
        return

    # Warn if Ollama might not be running
    from aladdin_core.llm import OllamaClient as _OC

    test_llm = _OC(cfg.ollama, "test")
    if not test_llm.is_available():
        log.warning(
            "Ollama not reachable at %s — start it with 'ollama serve'.",
            cfg.ollama.host,
        )

    assistant = Aladdin(
        cfg=cfg,
        text_only=args.text,
        no_wake_word=args.no_wake_word,
    )

    if args.once:
        assistant.run_once()
    elif args.daemon:
        assistant.run_daemon()
    else:
        assistant.run_loop()


if __name__ == "__main__":
    main()


# ---------------------------------------------------------------------------
# API server launcher (called by --api flag added below)
# ---------------------------------------------------------------------------


def run_api_server(cfg: AladdinConfig, host: str = "0.0.0.0", port: int = 8000) -> None:
    """Start the FastAPI REST server (requires uvicorn + fastapi)."""
    try:
        import uvicorn
    except ImportError:
        log.info("uvicorn not installed. Run: pip install fastapi uvicorn")

        sys.exit(1)
    from api.server import create_app

    app = create_app(cfg)
    log.info(f"\n  🌐 Aladdin API running at http://{host}:{port}")

    log.info(f"  📖 Docs:  http://{host}:{port}/docs")

    print(
        f"  🖥️  Web UI: open web/index.html in your browser (point to http://localhost:{port})\n"
    )
    uvicorn.run(app, host=host, port=port)
