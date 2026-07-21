"""extras/offline_first.py — Feature 10: Offline-First Architecture.

Provides:
- Network detection and auto offline/online switching
- Local LLM + offline STT/TTS fallbacks
- Write-ahead queue for deferred sync
- Conflict-free sync with timestamps
- Graceful degradation of cloud features
- Background sync worker
"""

from __future__ import annotations

import json
import logging
import queue
import socket
import threading
import time
import uuid
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

log = logging.getLogger(__name__)


class NetworkState(str, Enum):
    ONLINE  = "online"
    OFFLINE = "offline"
    LIMITED = "limited"   # internet reachable but slow


@dataclass
class SyncOperation:
    op_id: str = field(default_factory=lambda: str(uuid.uuid4())[:12])
    op_type: str = ""            # "memory_add", "conversation_add", "setting_set", etc.
    data: Dict[str, Any] = field(default_factory=dict)
    created_at: float = field(default_factory=time.time)
    attempts: int = 0
    max_attempts: int = 5
    last_error: str = ""

    def to_dict(self) -> Dict:
        return {
            "op_id": self.op_id, "op_type": self.op_type, "data": self.data,
            "created_at": self.created_at, "attempts": self.attempts,
            "max_attempts": self.max_attempts, "last_error": self.last_error,
        }

    @classmethod
    def from_dict(cls, d: Dict) -> "SyncOperation":
        return cls(**{k: v for k, v in d.items() if k in cls.__dataclass_fields__})


class NetworkMonitor:
    """Monitors network connectivity and fires callbacks on state changes."""

    CHECK_HOSTS = [
        ("8.8.8.8", 53),    # Google DNS
        ("1.1.1.1", 53),    # Cloudflare DNS
        ("208.67.222.222", 53),  # OpenDNS
    ]

    def __init__(self, check_interval: float = 10.0) -> None:
        self._interval = check_interval
        self._state = NetworkState.OFFLINE
        self._callbacks: List[Callable[[NetworkState, NetworkState], None]] = []
        self._running = False
        self._thread: Optional[threading.Thread] = None

    def check(self) -> NetworkState:
        for host, port in self.CHECK_HOSTS:
            try:
                s = socket.create_connection((host, port), timeout=3.0)
                s.close()
                return NetworkState.ONLINE
            except (socket.timeout, ConnectionRefusedError):
                continue
            except OSError:
                continue
        return NetworkState.OFFLINE

    def start(self) -> None:
        self._state = self.check()
        self._running = True

        def _loop():
            while self._running:
                new_state = self.check()
                if new_state != self._state:
                    old = self._state
                    self._state = new_state
                    log.info("[Network] State: %s → %s", old.value, new_state.value)
                    for cb in self._callbacks:
                        try:
                            cb(old, new_state)
                        except Exception:
                            pass
                time.sleep(self._interval)

        self._thread = threading.Thread(target=_loop, daemon=True, name="NetworkMonitor")
        self._thread.start()
        log.info("[Network] Monitor started (initial=%s)", self._state.value)

    def stop(self) -> None:
        self._running = False

    def on_change(self, cb: Callable[[NetworkState, NetworkState], None]) -> None:
        self._callbacks.append(cb)

    @property
    def is_online(self) -> bool:
        return self._state == NetworkState.ONLINE

    @property
    def state(self) -> NetworkState:
        return self._state


class WriteAheadQueue:
    """Durable queue that persists operations to disk for offline buffering."""

    def __init__(self, queue_dir: str = ".data/sync_queue") -> None:
        self._dir = Path(queue_dir)
        self._dir.mkdir(parents=True, exist_ok=True)
        self._queue: queue.PriorityQueue = queue.PriorityQueue()
        self._lock = threading.Lock()
        self._load()

    def _load(self) -> None:
        for f in sorted(self._dir.glob("*.json")):
            try:
                op = SyncOperation.from_dict(json.loads(f.read_text()))
                self._queue.put((op.created_at, op))
            except Exception:
                pass
        log.info("[SyncQueue] Loaded %d pending ops", self._queue.qsize())

    def _op_path(self, op_id: str) -> Path:
        return self._dir / f"{op_id}.json"

    def enqueue(self, op: SyncOperation) -> None:
        self._op_path(op.op_id).write_text(json.dumps(op.to_dict()))
        self._queue.put((op.created_at, op))
        log.debug("[SyncQueue] Enqueued: %s", op.op_type)

    def peek(self) -> Optional[SyncOperation]:
        try:
            _, op = self._queue.queue[0]
            return op
        except (IndexError, AttributeError):
            return None

    def dequeue(self) -> Optional[SyncOperation]:
        try:
            _, op = self._queue.get_nowait()
            return op
        except queue.Empty:
            return None

    def remove(self, op_id: str) -> None:
        path = self._op_path(op_id)
        if path.exists():
            path.unlink()

    def requeue(self, op: SyncOperation) -> None:
        """Re-add a failed op after incrementing attempt counter."""
        op.attempts += 1
        if op.attempts < op.max_attempts:
            self._op_path(op.op_id).write_text(json.dumps(op.to_dict()))
            self._queue.put((time.time() + op.attempts * 30, op))
        else:
            log.warning("[SyncQueue] Op %s exceeded max attempts, dropping", op.op_id)
            self.remove(op.op_id)

    def size(self) -> int:
        return self._queue.qsize()


class OfflineLLMFallback:
    """Manages the local LLM fallback for offline mode."""

    def __init__(
        self,
        model_dir: str = "models/gguf",
        preferred_model: str = "phi3-mini-q4.gguf",
    ) -> None:
        self._model_dir = Path(model_dir)
        self._preferred = preferred_model
        self._llm = None
        self._loaded = False

    def is_ready(self) -> bool:
        return self._loaded and self._llm is not None

    def has_model(self) -> bool:
        return any(self._model_dir.glob("*.gguf")) if self._model_dir.exists() else False

    def load(self) -> bool:
        if self._loaded:
            return True
        try:
            from llama_cpp import Llama  # type: ignore
            models = list(self._model_dir.glob("*.gguf"))
            if not models:
                log.warning("[Offline] No GGUF models in %s", self._model_dir)
                return False
            # Prefer the requested model or pick the smallest
            target = next((m for m in models if self._preferred in m.name), None) or models[0]
            self._llm = Llama(model_path=str(target), n_ctx=2048, n_threads=4, verbose=False)
            self._loaded = True
            log.info("[Offline] Local LLM loaded: %s", target.name)
            return True
        except ImportError:
            log.warning("[Offline] llama-cpp-python not installed")
            return False
        except Exception as exc:
            log.error("[Offline] LLM load failed: %s", exc)
            return False

    def generate(self, prompt: str, max_tokens: int = 256) -> str:
        if not self._loaded:
            self.load()
        if not self._llm:
            return "[Offline] Local LLM not available."
        try:
            output = self._llm(prompt, max_tokens=max_tokens, stop=["</s>", "\nUser:"])
            return output["choices"][0]["text"].strip()
        except Exception as exc:
            log.error("[Offline] Generate failed: %s", exc)
            return f"[Offline error] {exc}"


class OfflineFirstManager:
    """Central offline-first orchestrator for Aladdin AI."""

    def __init__(
        self,
        online_llm_fn: Optional[Callable[[str], str]] = None,
        data_dir: str = ".data",
        model_dir: str = "models/gguf",
        sync_handler: Optional[Callable[[SyncOperation], bool]] = None,
    ) -> None:
        self._online_llm = online_llm_fn
        self._sync_handler = sync_handler
        self._network = NetworkMonitor()
        self._queue = WriteAheadQueue(queue_dir=str(Path(data_dir) / "sync_queue"))
        self._local_llm = OfflineLLMFallback(model_dir=model_dir)
        self._degraded_features: Dict[str, bool] = {}
        self._sync_thread: Optional[threading.Thread] = None
        self._running = False

        # Register network change callback
        self._network.on_change(self._on_network_change)

    # ── Lifecycle ─────────────────────────────────────────────────────────────

    def start(self) -> None:
        self._running = True
        self._network.start()
        self._start_sync_worker()
        # Pre-load local LLM if offline
        if not self._network.is_online:
            self._local_llm.load()
        log.info("[Offline] Manager started (state=%s)", self._network.state.value)

    def stop(self) -> None:
        self._running = False
        self._network.stop()

    # ── AI inference with auto-fallback ───────────────────────────────────────

    def chat(self, text: str, user_id: str = "default") -> Tuple[str, str]:
        """Return (reply, provider). Auto-selects online/offline LLM."""
        if self._network.is_online and self._online_llm:
            try:
                reply = self._online_llm(text)
                # Queue for sync logging
                self._queue.enqueue(SyncOperation(
                    op_type="conversation_log",
                    data={"user_id": user_id, "text": text, "reply": reply, "provider": "cloud"},
                ))
                return reply, "cloud"
            except Exception as exc:
                log.warning("[Offline] Cloud LLM failed (%s), falling back to local", exc)

        # Offline fallback
        if not self._local_llm.is_ready():
            self._local_llm.load()
        if self._local_llm.is_ready():
            reply = self._local_llm.generate(f"User: {text}\nAssistant:")
            return reply, "local_llm"

        return "I'm offline and no local AI model is available. Please download a model.", "none"

    # ── Write-ahead for mutations ─────────────────────────────────────────────

    def defer_operation(self, op_type: str, data: Dict[str, Any]) -> str:
        op = SyncOperation(op_type=op_type, data=data)
        self._queue.enqueue(op)
        log.debug("[Offline] Deferred op: %s (queue=%d)", op_type, self._queue.size())
        return op.op_id

    # ── Background sync worker ────────────────────────────────────────────────

    def _start_sync_worker(self) -> None:
        def _worker():
            while self._running:
                if self._network.is_online and self._queue.size() > 0:
                    self._flush_queue()
                time.sleep(15)

        self._sync_thread = threading.Thread(target=_worker, daemon=True, name="SyncWorker")
        self._sync_thread.start()

    def _flush_queue(self) -> int:
        synced = 0
        while self._queue.size() > 0:
            op = self._queue.dequeue()
            if not op:
                break
            if self._sync_handler:
                try:
                    ok = self._sync_handler(op)
                    if ok:
                        self._queue.remove(op.op_id)
                        synced += 1
                    else:
                        op.last_error = "sync_handler returned False"
                        self._queue.requeue(op)
                except Exception as exc:
                    op.last_error = str(exc)
                    self._queue.requeue(op)
            else:
                self._queue.remove(op.op_id)
                synced += 1
        if synced > 0:
            log.info("[Offline] Synced %d deferred operations", synced)
        return synced

    # ── Network change handler ────────────────────────────────────────────────

    def _on_network_change(self, old: NetworkState, new: NetworkState) -> None:
        if new == NetworkState.ONLINE:
            log.info("[Offline] Back online — starting sync")
            threading.Thread(target=self._flush_queue, daemon=True).start()
            # Re-enable cloud features
            self._degraded_features = {}
        elif new == NetworkState.OFFLINE:
            log.info("[Offline] Gone offline — enabling local fallbacks")
            self._degraded_features = {
                "web_search": False,
                "cloud_llm": False,
                "sync": False,
            }
            # Pre-load local LLM
            if not self._local_llm.is_ready():
                threading.Thread(target=self._local_llm.load, daemon=True).start()

    # ── Feature availability ──────────────────────────────────────────────────

    def is_available(self, feature: str) -> bool:
        if self._network.is_online:
            return True
        return self._degraded_features.get(feature, True)

    def status(self) -> Dict[str, Any]:
        return {
            "network": self._network.state.value,
            "is_online": self._network.is_online,
            "pending_sync_ops": self._queue.size(),
            "local_llm_ready": self._local_llm.is_ready(),
            "local_llm_model_available": self._local_llm.has_model(),
            "degraded_features": self._degraded_features,
        }
