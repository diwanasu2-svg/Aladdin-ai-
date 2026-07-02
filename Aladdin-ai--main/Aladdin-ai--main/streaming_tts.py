"""
Phase 1 — Real Streaming TTS
==============================
Token-by-token speech output via Piper TTS.
- Sentence-level streaming with audio pre-buffering
- Parallel synthesis + playback pipeline
- TTS interruption support via threading Event
- Queue-managed audio chunks for instant start
- Incremental text accumulation and sentence flushing
"""

from __future__ import annotations

import io
import logging
import queue
import re
import subprocess
import threading
import time
from pathlib import Path
from typing import Callable, Generator, Iterator, List, Optional

import numpy as np

log = logging.getLogger(__name__)

# PCM constants from Piper (--output_raw = 16-bit LE mono, 22050 Hz)
PIPER_SAMPLE_RATE = 22050
PIPER_DTYPE = np.int16
_SENTENCE_RE = re.compile(r"(?<=[.!?;])\s+|(?<=\n)")

# ─────────────────────────────────────────────────────────────────────────────


def _find_piper_binary() -> Optional[Path]:
    """Locate the Piper binary on common paths or $PATH."""
    import shutil

    candidates = [
        Path("models/piper/piper"),
        Path("/usr/local/bin/piper"),
        Path("/usr/bin/piper"),
        Path("piper/piper"),
    ]
    for p in candidates:
        if p.is_file():
            return p
    found = shutil.which("piper")
    return Path(found) if found else None


def _split_sentences(text: str, min_chars: int = 5) -> List[str]:
    """Split text on sentence boundaries; merge very short trailing fragments."""
    parts = [s.strip() for s in _SENTENCE_RE.split(text) if s.strip()]
    merged: List[str] = []
    carry = ""
    for part in parts:
        carry = (carry + " " + part).strip() if carry else part
        if len(carry) >= min_chars:
            merged.append(carry)
            carry = ""
    if carry:
        if merged:
            merged[-1] += " " + carry
        else:
            merged.append(carry)
    return merged


class StreamingTTS:
    """
    Streaming Piper TTS — sentence-by-sentence pipeline with pre-buffering.

    Usage
    -----
    tts = StreamingTTS(model_path="voices/en_US-amy-medium.onnx")
    for chunk in tts.synthesize_streaming("Hello world! How are you?"):
        play(chunk)          # float32 PCM at 22 050 Hz
    """

    def __init__(
        self,
        sample_rate: int = PIPER_SAMPLE_RATE,
        model_path: str = "voices/en_US-amy-medium.onnx",
        config_path: Optional[str] = None,
        prebuffer_sentences: int = 1,
        max_queue: int = 64,
    ):
        self.sample_rate = sample_rate
        self.model_path = model_path
        self.config_path = config_path
        self.prebuffer_sentences = max(1, prebuffer_sentences)

        self._piper_bin: Optional[Path] = _find_piper_binary()
        self._audio_queue: "queue.Queue[Optional[np.ndarray]]" = queue.Queue(
            maxsize=max_queue
        )
        self._interrupt = threading.Event()
        self._lock = threading.Lock()

        if self._piper_bin:
            log.info("StreamingTTS: Piper binary → %s", self._piper_bin)
        else:
            log.warning(
                "StreamingTTS: Piper binary not found; synthesis will be silent."
            )

    # ── Public API ─────────────────────────────────────────────────────────

    def interrupt(self) -> None:
        """Signal active synthesis to stop immediately."""
        self._interrupt.set()
        log.info("StreamingTTS: interrupt requested")

    def reset_interrupt(self) -> None:
        """Clear the interrupt flag for the next synthesis."""
        self._interrupt.clear()

    def synthesize_streaming(
        self,
        text: str,
        chunk_callback: Optional[Callable[[np.ndarray], None]] = None,
    ) -> Generator[np.ndarray, None, None]:
        """
        Yield float32 audio chunks as Piper synthesises them sentence-by-sentence.
        Synthesis runs in a background thread so the first chunk arrives while
        later sentences are still being processed.
        """
        self.reset_interrupt()
        if not text or not text.strip():
            return

        sentences = _split_sentences(text)
        synth_queue: "queue.Queue[Optional[np.ndarray]]" = queue.Queue(maxsize=128)

        def _synthesize_worker():
            for sentence in sentences:
                if self._interrupt.is_set():
                    break
                try:
                    chunk = self._synthesize_text(sentence)
                    if chunk is not None:
                        synth_queue.put(chunk)
                except Exception as exc:
                    log.error("StreamingTTS synthesis error: %s", exc)
            synth_queue.put(None)  # sentinel

        thread = threading.Thread(target=_synthesize_worker, daemon=True)
        thread.start()

        # Pre-buffer: wait until at least `prebuffer_sentences` chunks arrive
        # before yielding, so playback starts without stutter.
        buf: List[np.ndarray] = []
        prebuffered = False

        while True:
            try:
                item = synth_queue.get(timeout=10.0)
            except queue.Empty:
                break
            if item is None:
                break
            if self._interrupt.is_set():
                break

            buf.append(item)
            if not prebuffered and len(buf) >= self.prebuffer_sentences:
                prebuffered = True

            if prebuffered:
                for chunk in buf:
                    if chunk_callback:
                        try:
                            chunk_callback(chunk)
                        except Exception:
                            pass
                    yield chunk
                buf.clear()

        # Drain remaining buffer
        for chunk in buf:
            if self._interrupt.is_set():
                break
            if chunk_callback:
                try:
                    chunk_callback(chunk)
                except Exception:
                    pass
            yield chunk

        thread.join(timeout=5.0)

    def stream_token_by_token(
        self,
        token_stream: Iterator[str],
        chunk_callback: Optional[Callable[[np.ndarray], None]] = None,
    ) -> Generator[np.ndarray, None, None]:
        """
        Accept an iterator of LLM tokens (strings) and produce audio in
        real-time by accumulating tokens into sentences before synthesising.
        This enables token-by-token TTS for streaming LLMs.
        """
        self.reset_interrupt()
        pending = ""

        for token in token_stream:
            if self._interrupt.is_set():
                break
            pending += token

            # Check if we have a complete sentence
            sentences = _split_sentences(pending)
            if len(sentences) > 1:
                # Flush all but the last incomplete fragment
                for sentence in sentences[:-1]:
                    if self._interrupt.is_set():
                        break
                    chunk = self._synthesize_text(sentence)
                    if chunk is not None:
                        if chunk_callback:
                            chunk_callback(chunk)
                        yield chunk
                pending = sentences[-1]  # keep remainder

        # Flush final sentence
        if pending.strip() and not self._interrupt.is_set():
            chunk = self._synthesize_text(pending.strip())
            if chunk is not None:
                if chunk_callback:
                    chunk_callback(chunk)
                yield chunk

    # ── Internal helpers ───────────────────────────────────────────────────

    def _synthesize_text(self, text: str) -> Optional[np.ndarray]:
        """
        Run Piper in a subprocess for a single sentence.
        Returns raw float32 audio or None on failure.
        """
        if not text.strip():
            return None
        if self._piper_bin is None:
            log.debug("Piper not available; skipping synthesis of: %s", text[:40])
            return None

        model_path = Path(self.model_path)
        config_path = (
            Path(self.config_path)
            if self.config_path
            else model_path.with_suffix(".onnx.json")
        )

        if not model_path.exists():
            log.warning("Piper model not found: %s", model_path)
            return None

        cmd = [
            str(self._piper_bin),
            "--model",
            str(model_path),
            "--output_raw",
        ]
        if config_path.exists():
            cmd += ["--config", str(config_path)]

        try:
            result = subprocess.run(
                cmd,
                input=text.encode("utf-8"),
                capture_output=True,
                timeout=30,
            )
            if result.returncode != 0:
                log.error(
                    "Piper exited %d: %s",
                    result.returncode,
                    result.stderr.decode("utf-8", errors="replace")[:200],
                )
                return None
            raw = result.stdout
            if not raw:
                return None
            audio_int16 = np.frombuffer(raw, dtype=np.int16)
            return audio_int16.astype(np.float32) / 32768.0
        except subprocess.TimeoutExpired:
            log.error("Piper synthesis timed out for: %s", text[:40])
            return None
        except Exception as exc:
            log.error("Piper subprocess error: %s", exc)
            return None

    # ── Legacy queue API (kept for backward compatibility) ─────────────────

    def queue_audio(self, audio: np.ndarray) -> bool:
        try:
            self._audio_queue.put_nowait(audio)
            return True
        except queue.Full:
            return False

    def get_queued_audio(self, timeout: float = 1.0) -> Optional[np.ndarray]:
        try:
            return self._audio_queue.get(timeout=timeout)
        except queue.Empty:
            return None

    @staticmethod
    def _bytes_to_audio(audio_bytes: bytes) -> Optional[np.ndarray]:
        if not audio_bytes:
            return None
        audio_int16 = np.frombuffer(audio_bytes, dtype=np.int16)
        return audio_int16.astype(np.float32) / 32768.0
