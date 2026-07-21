"""Multilingual TTS Engine — Features 5, 6, 7, 9, 13, 15.

Supports Hindi, Gujarati, and English TTS with:
- Piper TTS (primary, offline): Hindi and English voices
- Google Cloud TTS (fallback for Gujarati if Piper Gujarati unavailable)
- Automatic TTS language switching (<100 ms)
- Voice model preloading on startup
- Language fallback chain: Gujarati → Hindi → English
- Voice model caching for faster switching
- Streaming output support
"""

from __future__ import annotations

import io
import logging
import os
import shutil
import subprocess
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Dict, Iterator, List, Optional, Tuple

from language_detector import LANG_ENGLISH, LANG_GUJARATI, LANG_HINDI
from text_normalizer import normalize_for_tts

log = logging.getLogger(__name__)


# ── Voice model registry ──────────────────────────────────────────────────────


@dataclass
class VoiceModel:
    """Descriptor for a single TTS voice model."""

    language: str  # "hi", "gu", "en"
    engine: str  # "piper" | "gtts" | "azure" | "system"
    model_path: Optional[str]  # path to .onnx file (Piper only)
    config_path: Optional[str]  # path to .onnx.json (Piper only)
    sample_rate: int = 22_050
    priority: int = 0  # higher = preferred
    is_fallback: bool = False  # True if this is a fallback voice
    display_name: str = ""

    @property
    def is_available(self) -> bool:
        if self.engine == "piper":
            return bool(self.model_path and Path(self.model_path).exists())
        if self.engine == "gtts":
            return _gtts_available()
        if self.engine == "system":
            return True  # Android TTS is always present
        return False


def _gtts_available() -> bool:
    try:
        import gtts  # type: ignore  # noqa: F401

        return True
    except ImportError:
        return False


# ── Language fallback chain — Feature 9 ──────────────────────────────────────

FALLBACK_CHAIN: Dict[str, List[str]] = {
    LANG_GUJARATI: [LANG_GUJARATI, LANG_HINDI, LANG_ENGLISH],
    LANG_HINDI: [LANG_HINDI, LANG_ENGLISH],
    LANG_ENGLISH: [LANG_ENGLISH],
}


@dataclass
class MultilingualTTSConfig:
    """Configuration for the multilingual TTS engine."""

    # Piper binary path
    piper_binary: str = "models/piper/piper"

    # Voice model paths — resolved relative to this config's base_dir
    base_dir: str = "."

    # English voice (Piper)
    en_voice: str = "models/piper/en_US-lessac-medium.onnx"
    en_config: str = "models/piper/en_US-lessac-medium.onnx.json"

    # Hindi voice (Piper) — https://huggingface.co/rhasspy/piper-voices tree/main/hi/hi_IN/
    hi_voice: str = "models/piper/hi_IN-hindi_male-medium.onnx"
    hi_config: str = "models/piper/hi_IN-hindi_male-medium.onnx.json"

    # Gujarati voice (Piper) — if not available, falls back to gTTS/Hindi
    gu_voice: str = "models/piper/gu_IN-cmu_indic-medium.onnx"
    gu_config: str = "models/piper/gu_IN-cmu_indic-medium.onnx.json"

    # gTTS fallback settings (Gujarati → Google TTS)
    gtts_enabled: bool = True
    gtts_slow: bool = False

    # Streaming
    streaming_enabled: bool = True
    chunk_size_bytes: int = 4096

    # Pre-load voices on startup
    preload_on_startup: bool = True

    # Speaking rate (1.0 = normal)
    speaking_rate: float = 1.0

    # Notify user when fallback is used
    fallback_notification: bool = True

    @classmethod
    def from_config(cls, cfg) -> "MultilingualTTSConfig":
        """Build from the project's main config object."""
        piper = getattr(cfg, "piper", None)
        ml = getattr(cfg, "multilingual", None)

        kwargs: dict = {}
        if piper:
            kwargs["en_voice"] = getattr(piper, "model_path", cls.en_voice)
            kwargs["streaming_enabled"] = getattr(piper, "streaming_enabled", True)
        if ml:
            for attr in (
                "hi_voice",
                "hi_config",
                "gu_voice",
                "gu_config",
                "piper_binary",
                "gtts_enabled",
                "preload_on_startup",
            ):
                if hasattr(ml, attr):
                    kwargs[attr] = getattr(ml, attr)
        return cls(**kwargs)


class MultilingualTTSEngine:
    """Multilingual TTS with automatic language switching and fallback.

    Usage::

        engine = MultilingualTTSEngine(config)
        engine.preload()            # warm up all available voices
        engine.speak("नमस्ते!", "hi")  # Hindi
        engine.speak("hello!", "en")   # English
        # Iterate streaming chunks:
        for chunk in engine.stream("Kem cho?", "gu"):
            audio_player.write(chunk)
    """

    def __init__(self, config: Optional[MultilingualTTSConfig] = None):
        self.config = config or MultilingualTTSConfig()
        self._voices: Dict[str, List[VoiceModel]] = {}
        self._preloaded: set[str] = set()
        self._lock = threading.Lock()
        self._build_voice_registry()
        log.info(
            "MultilingualTTSEngine initialized (voices: %s)", list(self._voices.keys())
        )

    # ── Public API ────────────────────────────────────────────────────────────

    def preload(self) -> None:
        """Preload all available voice models in background — Feature 13."""
        log.info("Preloading TTS voice models…")
        start = time.monotonic()

        def _do_preload():
            for lang in [LANG_ENGLISH, LANG_HINDI, LANG_GUJARATI]:
                voice = self._best_voice(lang)
                if voice and voice.engine == "piper" and voice.is_available:
                    self._warm_piper(voice)
                    self._preloaded.add(lang)
                    log.info("Preloaded %s voice (%s)", lang, voice.display_name)

        t = threading.Thread(target=_do_preload, daemon=True, name="tts-preload")
        t.start()
        if self.config.preload_on_startup:
            t.join(timeout=10.0)  # wait up to 10s for critical voices
        elapsed = time.monotonic() - start
        log.info("Voice preload complete in %.1fs", elapsed)

    def speak(
        self,
        text: str,
        language: str,
        on_fallback: Optional[Callable[[str, str, str], None]] = None,
    ) -> bytes:
        """Synthesize *text* in *language* and return raw PCM bytes.

        Args:
            text:        Text to synthesize.
            language:    Detected language code.
            on_fallback: Optional callback(original_lang, fallback_lang, reason)

        Returns:
            Raw 16-bit PCM audio bytes at the voice's sample rate.
        """
        text = normalize_for_tts(text, language)
        if not text:
            return b""

        voice, actual_lang = self._resolve_voice_with_fallback(language)
        if actual_lang != language and on_fallback:
            on_fallback(language, actual_lang, f"Voice for '{language}' unavailable")

        if voice is None:
            log.error(
                "No TTS voice available for language '%s' (fallback chain exhausted)",
                language,
            )
            return b""

        return self._synthesize(text, voice, actual_lang)

    def stream(
        self,
        text: str,
        language: str,
        on_fallback: Optional[Callable[[str, str, str], None]] = None,
    ) -> Iterator[bytes]:
        """Stream TTS audio chunks for *text* in *language*.

        Yields raw PCM chunks as soon as they are available (token-by-token latency).
        """
        text = normalize_for_tts(text, language)
        if not text:
            return

        voice, actual_lang = self._resolve_voice_with_fallback(language)
        if actual_lang != language and on_fallback:
            on_fallback(language, actual_lang, f"Voice for '{language}' unavailable")

        if voice is None:
            log.error("No TTS voice available for language '%s'", language)
            return

        yield from self._stream_synthesize(text, voice, actual_lang)

    def available_languages(self) -> List[str]:
        """Return languages that have at least one available voice."""
        return [
            lang
            for lang, voices in self._voices.items()
            if any(v.is_available for v in voices)
        ]

    def voice_for(self, language: str) -> Optional[VoiceModel]:
        """Return the best available voice for *language*."""
        return self._best_voice(language)

    # ── Voice registry ────────────────────────────────────────────────────────

    def _build_voice_registry(self) -> None:
        cfg = self.config
        base = Path(cfg.base_dir)

        self._voices = {
            LANG_ENGLISH: [
                VoiceModel(
                    language=LANG_ENGLISH,
                    engine="piper",
                    model_path=str(base / cfg.en_voice),
                    config_path=str(base / cfg.en_config),
                    priority=10,
                    display_name="en_US-lessac-medium",
                ),
            ],
            LANG_HINDI: [
                VoiceModel(
                    language=LANG_HINDI,
                    engine="piper",
                    model_path=str(base / cfg.hi_voice),
                    config_path=str(base / cfg.hi_config),
                    priority=10,
                    display_name="hi_IN-hindi_male-medium",
                ),
                # Fallback: use gTTS for Hindi if Piper model missing
                VoiceModel(
                    language=LANG_HINDI,
                    engine="gtts",
                    model_path=None,
                    config_path=None,
                    priority=5,
                    is_fallback=True,
                    display_name="gtts-hi",
                ),
            ],
            LANG_GUJARATI: [
                VoiceModel(
                    language=LANG_GUJARATI,
                    engine="piper",
                    model_path=str(base / cfg.gu_voice),
                    config_path=str(base / cfg.gu_config),
                    priority=10,
                    display_name="gu_IN-cmu_indic-medium",
                ),
                # Fallback: gTTS Gujarati
                VoiceModel(
                    language=LANG_GUJARATI,
                    engine="gtts",
                    model_path=None,
                    config_path=None,
                    priority=5,
                    is_fallback=True,
                    display_name="gtts-gu",
                ),
            ],
        }

    def _best_voice(self, language: str) -> Optional[VoiceModel]:
        voices = self._voices.get(language, [])
        available = [v for v in voices if v.is_available]
        if not available:
            return None
        return max(available, key=lambda v: v.priority)

    def _resolve_voice_with_fallback(
        self, language: str
    ) -> Tuple[Optional[VoiceModel], str]:
        """Follow the fallback chain and return (voice, actual_language)."""
        chain = FALLBACK_CHAIN.get(language, [language])
        for lang in chain:
            voice = self._best_voice(lang)
            if voice is not None:
                if lang != language:
                    log.warning(
                        "TTS fallback: '%s' → '%s' (voice: %s)",
                        language,
                        lang,
                        voice.display_name,
                    )
                return voice, lang
        return None, language

    # ── Synthesis ─────────────────────────────────────────────────────────────

    def _synthesize(self, text: str, voice: VoiceModel, language: str) -> bytes:
        if voice.engine == "piper":
            return self._piper_synthesize(text, voice)
        if voice.engine == "gtts":
            return self._gtts_synthesize(text, language)
        log.error("Unknown TTS engine: %s", voice.engine)
        return b""

    def _stream_synthesize(
        self, text: str, voice: VoiceModel, language: str
    ) -> Iterator[bytes]:
        if voice.engine == "piper":
            yield from self._piper_stream(text, voice)
        elif voice.engine == "gtts":
            data = self._gtts_synthesize(text, language)
            for i in range(0, len(data), self.config.chunk_size_bytes):
                yield data[i : i + self.config.chunk_size_bytes]
        else:
            log.error("Unknown TTS engine: %s", voice.engine)

    # ── Piper TTS ─────────────────────────────────────────────────────────────

    def _piper_binary(self) -> str:
        return str(Path(self.config.base_dir) / self.config.piper_binary)

    def _warm_piper(self, voice: VoiceModel) -> None:
        """Warm up the Piper model by synthesizing a silent/short phrase."""
        try:
            self._piper_synthesize(" ", voice)
            log.debug("Warmed up Piper voice: %s", voice.display_name)
        except Exception as e:
            log.debug("Piper warm-up skipped (%s): %s", voice.display_name, e)

    def _piper_synthesize(self, text: str, voice: VoiceModel) -> bytes:
        piper_bin = self._piper_binary()
        if not Path(piper_bin).exists():
            raise FileNotFoundError(f"Piper binary not found: {piper_bin}")
        if not voice.model_path or not Path(voice.model_path).exists():
            raise FileNotFoundError(f"Piper model not found: {voice.model_path}")

        cmd = self._build_piper_cmd(piper_bin, voice)
        try:
            result = subprocess.run(
                cmd,
                input=text.encode("utf-8"),
                capture_output=True,
                timeout=30,
            )
            if result.returncode != 0:
                log.error(
                    "Piper error: %s", result.stderr.decode("utf-8", errors="replace")
                )
                return b""
            return result.stdout
        except subprocess.TimeoutExpired:
            log.error("Piper synthesis timed out")
            return b""

    def _piper_stream(self, text: str, voice: VoiceModel) -> Iterator[bytes]:
        piper_bin = self._piper_binary()
        if not Path(piper_bin).exists():
            log.error("Piper binary not found: %s", piper_bin)
            return

        cmd = self._build_piper_cmd(piper_bin, voice)
        try:
            proc = subprocess.Popen(
                cmd,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            proc.stdin.write(text.encode("utf-8"))
            proc.stdin.close()

            chunk_size = self.config.chunk_size_bytes
            while True:
                chunk = proc.stdout.read(chunk_size)
                if not chunk:
                    break
                yield chunk

            proc.wait(timeout=5)
        except Exception as e:
            log.error("Piper stream error: %s", e)

    def _build_piper_cmd(self, piper_bin: str, voice: VoiceModel) -> List[str]:
        cmd = [
            piper_bin,
            "--model",
            voice.model_path,
            "--output_raw",
            "--length_scale",
            str(1.0 / max(self.config.speaking_rate, 0.1)),
        ]
        if voice.config_path and Path(voice.config_path).exists():
            cmd += ["--config", voice.config_path]
        return cmd

    # ── gTTS fallback ─────────────────────────────────────────────────────────

    def _gtts_synthesize(self, text: str, language: str) -> bytes:
        try:
            from gtts import gTTS  # type: ignore
            import tempfile

            tts = gTTS(text=text, lang=language, slow=self.config.gtts_slow)
            with tempfile.NamedTemporaryFile(suffix=".mp3", delete=False) as tmp:
                tts.save(tmp.name)
                with open(tmp.name, "rb") as f:
                    data = f.read()
            os.unlink(tmp.name)
            # gTTS produces MP3; ideally convert to PCM via ffmpeg if available
            return self._mp3_to_pcm(data)
        except ImportError:
            log.error("gTTS not installed. Install with: pip install gtts")
            return b""
        except Exception as e:
            log.error("gTTS synthesis error: %s", e)
            return b""

    @staticmethod
    def _mp3_to_pcm(mp3_data: bytes) -> bytes:
        """Convert MP3 bytes to raw PCM 16-bit 22050Hz mono via ffmpeg."""
        if not shutil.which("ffmpeg"):
            log.warning(
                "ffmpeg not found; returning raw MP3 bytes (may not play correctly)"
            )
            return mp3_data
        try:
            result = subprocess.run(
                [
                    "ffmpeg",
                    "-y",
                    "-i",
                    "pipe:0",
                    "-f",
                    "s16le",
                    "-ar",
                    "22050",
                    "-ac",
                    "1",
                    "pipe:1",
                ],
                input=mp3_data,
                capture_output=True,
                timeout=15,
            )
            return result.stdout if result.returncode == 0 else mp3_data
        except Exception as e:
            log.warning("ffmpeg conversion failed: %s", e)
            return mp3_data


# ── Convenience singleton ─────────────────────────────────────────────────────

_engine: Optional[MultilingualTTSEngine] = None


def get_tts_engine(
    config: Optional[MultilingualTTSConfig] = None,
) -> MultilingualTTSEngine:
    """Return or create the shared MultilingualTTSEngine singleton."""
    global _engine
    if _engine is None:
        _engine = MultilingualTTSEngine(config)
    return _engine
