"""models/streaming_manager.py — Phase 14, Feature 4: Streaming Everywhere.

Token-by-token streaming for all AI providers with WebSocket and
Server-Sent Events support, plus real-time UI update callbacks.
"""

from __future__ import annotations

import asyncio
import json
import logging
import queue
import threading
import time
from dataclasses import dataclass, field
from enum import Enum
from typing import AsyncGenerator, Callable, Dict, Generator, List, Optional, Set

log = logging.getLogger(__name__)


class StreamProtocol(str, Enum):
    CALLBACK  = "callback"   # direct Python callback
    SSE       = "sse"        # Server-Sent Events
    WEBSOCKET = "websocket"  # WebSocket
    QUEUE     = "queue"      # thread-safe queue


@dataclass
class StreamChunk:
    text: str
    is_final: bool = False
    provider: str = ""
    timestamp: float = field(default_factory=time.monotonic)
    token_count: int = 0


class TokenBuffer:
    """Accumulates streamed tokens and flushes at word/sentence boundaries."""

    def __init__(self, flush_on: str = " ", min_chars: int = 1) -> None:
        self._buf: List[str] = []
        self._flush_on = flush_on
        self._min_chars = min_chars

    def push(self, token: str) -> Optional[str]:
        self._buf.append(token)
        joined = "".join(self._buf)
        if len(joined) >= self._min_chars and (
            token.endswith(self._flush_on) or "\n" in token
        ):
            self._buf.clear()
            return joined
        return None

    def flush(self) -> str:
        result = "".join(self._buf)
        self._buf.clear()
        return result


class StreamingSession:
    """Represents a single streaming inference session."""

    def __init__(
        self,
        session_id: str,
        protocol: StreamProtocol = StreamProtocol.CALLBACK,
        ui_callback: Optional[Callable[[StreamChunk], None]] = None,
    ) -> None:
        self.session_id = session_id
        self.protocol = protocol
        self.ui_callback = ui_callback
        self._tokens: List[str] = []
        self._done = threading.Event()
        self._queue: queue.Queue[Optional[StreamChunk]] = queue.Queue()
        self._subscribers: Set[asyncio.Queue] = set()

    def on_token(self, token: str, provider: str = "") -> None:
        chunk = StreamChunk(text=token, provider=provider, token_count=len(self._tokens))
        self._tokens.append(token)
        self._queue.put(chunk)
        if self.ui_callback:
            try:
                self.ui_callback(chunk)
            except Exception as exc:
                log.warning("[Stream] UI callback error: %s", exc)

    def finish(self) -> str:
        final_text = "".join(self._tokens)
        final_chunk = StreamChunk(text=final_text, is_final=True, token_count=len(self._tokens))
        self._queue.put(final_chunk)
        self._queue.put(None)  # sentinel
        self._done.set()
        return final_text

    def token_generator(self) -> Generator[StreamChunk, None, None]:
        """Blocking generator — yields each chunk as it arrives."""
        while True:
            chunk = self._queue.get()
            if chunk is None:
                break
            yield chunk

    def sse_generator(self) -> Generator[str, None, None]:
        """Server-Sent Events format generator."""
        for chunk in self.token_generator():
            data = json.dumps({"text": chunk.text, "done": chunk.is_final})
            yield f"data: {data}\n\n"
        yield "data: [DONE]\n\n"

    async def async_token_generator(self) -> AsyncGenerator[StreamChunk, None]:
        """Async generator for WebSocket/asyncio consumers."""
        # Task 17: get_running_loop() is safe here — we're inside an async context
        loop = asyncio.get_running_loop()
        while True:
            chunk = await loop.run_in_executor(None, self._queue.get)
            if chunk is None:
                break
            yield chunk

    @property
    def full_text(self) -> str:
        return "".join(self._tokens)

    def wait(self, timeout: Optional[float] = None) -> bool:
        return self._done.wait(timeout=timeout)


class StreamingManager:
    """Manages all active streaming sessions and routes tokens correctly."""

    def __init__(self) -> None:
        self._sessions: Dict[str, StreamingSession] = {}
        self._lock = threading.Lock()

    # ------------------------------------------------------------------
    # Session lifecycle
    # ------------------------------------------------------------------

    def create_session(
        self,
        session_id: Optional[str] = None,
        protocol: StreamProtocol = StreamProtocol.CALLBACK,
        ui_callback: Optional[Callable[[StreamChunk], None]] = None,
    ) -> StreamingSession:
        import uuid
        sid = session_id or str(uuid.uuid4())
        session = StreamingSession(sid, protocol=protocol, ui_callback=ui_callback)
        with self._lock:
            self._sessions[sid] = session
        log.debug("[Streaming] Created session %s (%s)", sid, protocol.value)
        return session

    def get_session(self, session_id: str) -> Optional[StreamingSession]:
        return self._sessions.get(session_id)

    def close_session(self, session_id: str) -> None:
        with self._lock:
            self._sessions.pop(session_id, None)

    # ------------------------------------------------------------------
    # Streaming wrappers
    # ------------------------------------------------------------------

    def wrap_sync_callback(
        self,
        session: StreamingSession,
        provider_name: str = "",
    ) -> Callable[[str], None]:
        """Returns a callback that forwards tokens to the session."""
        def _cb(token: str) -> None:
            session.on_token(token, provider=provider_name)
        return _cb

    def stream_in_thread(
        self,
        session: StreamingSession,
        inference_fn: Callable[[Callable[[str], None]], str],
        provider_name: str = "",
    ) -> threading.Thread:
        """Run inference in a background thread, streaming tokens to the session."""
        def _run() -> None:
            try:
                cb = self.wrap_sync_callback(session, provider_name)
                inference_fn(cb)
            except Exception as exc:
                log.error("[Streaming] Inference error: %s", exc)
            finally:
                session.finish()

        t = threading.Thread(target=_run, daemon=True)
        t.start()
        return t

    # ------------------------------------------------------------------
    # Voice streaming helpers
    # ------------------------------------------------------------------

    def stream_voice_chunks(
        self,
        session: StreamingSession,
        tts_fn: Callable[[str], bytes],
        chunk_size_chars: int = 80,
    ) -> Generator[bytes, None, None]:
        """Stream TTS audio as text accumulates in chunks."""
        buffer = TokenBuffer(flush_on=".", min_chars=chunk_size_chars)
        for chunk in session.token_generator():
            flushed = buffer.push(chunk.text)
            if flushed:
                audio = tts_fn(flushed)
                if audio:
                    yield audio
            if chunk.is_final:
                remainder = buffer.flush()
                if remainder.strip():
                    audio = tts_fn(remainder)
                    if audio:
                        yield audio

    # ------------------------------------------------------------------
    # SSE Flask/FastAPI integration helper
    # ------------------------------------------------------------------

    @staticmethod
    def make_sse_response(session: StreamingSession) -> Generator[str, None, None]:
        """Use directly as a Flask/FastAPI StreamingResponse body."""
        yield from session.sse_generator()

    # ------------------------------------------------------------------
    # Active sessions info
    # ------------------------------------------------------------------

    def active_sessions(self) -> List[str]:
        return list(self._sessions.keys())
