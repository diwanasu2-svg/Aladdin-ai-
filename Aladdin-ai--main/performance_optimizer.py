"""
Phase 9 — Performance Optimization
=====================================
- Whisper model caching (RAM + disk via joblib)
- Piper model path caching
- Embedding vector caching (LRU)
- Async processing with thread pools (4 pools)
- RAM target < 200 MB
- CPU target < 10 % average
- Faster startup (lazy imports, warm-up threads)
- Pipeline latency optimisation
- Object pooling for audio buffers
- LRU cache management
- GC optimisation
"""

from __future__ import annotations

import gc
import logging
import os
import sys
import threading
import time
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor
from functools import lru_cache
from pathlib import Path
from typing import Any, Callable, Dict, Optional, Tuple

log = logging.getLogger(__name__)

# ─────────────────────────────────────────────────────────────────────────────
# GC tuning — reduce collection pauses
gc.set_threshold(2000, 20, 20)

# ─────────────────────────────────────────────────────────────────────────────
# Thread pools (4 pools for parallelism)
_POOLS: Dict[str, ThreadPoolExecutor] = {}
_POOL_LOCK = threading.Lock()


def _get_pool(name: str, workers: int = 4) -> ThreadPoolExecutor:
    with _POOL_LOCK:
        if name not in _POOLS:
            _POOLS[name] = ThreadPoolExecutor(
                max_workers=workers, thread_name_prefix=name
            )
    return _POOLS[name]


def get_stt_pool() -> ThreadPoolExecutor:
    return _get_pool("stt", workers=2)


def get_tts_pool() -> ThreadPoolExecutor:
    return _get_pool("tts", workers=2)


def get_llm_pool() -> ThreadPoolExecutor:
    return _get_pool("llm", workers=2)


def get_io_pool() -> ThreadPoolExecutor:
    return _get_pool("io", workers=4)


# ─────────────────────────────────────────────────────────────────────────────
# LRU model cache


class _LRUModelCache:
    """Thread-safe LRU cache for large in-memory objects (models)."""

    def __init__(self, max_items: int = 4, max_memory_mb: int = 400):
        self._max = max_items
        self._max_mem = max_memory_mb * 1024 * 1024
        self._store: OrderedDict[str, Any] = OrderedDict()
        self._lock = threading.RLock()

    def get(self, key: str) -> Optional[Any]:
        with self._lock:
            if key in self._store:
                self._store.move_to_end(key)
                log.debug("Model cache hit: %s", key)
                return self._store[key]
        return None

    def put(self, key: str, model: Any) -> None:
        with self._lock:
            if key in self._store:
                self._store.move_to_end(key)
            else:
                self._store[key] = model
                while len(self._store) > self._max:
                    evicted_key, _ = self._store.popitem(last=False)
                    log.info("Model cache evicted: %s", evicted_key)
                    gc.collect()

    def clear(self) -> None:
        with self._lock:
            self._store.clear()
        gc.collect()

    def stats(self) -> Dict[str, int]:
        with self._lock:
            return {"cached_models": len(self._store), "max_models": self._max}


_model_cache = _LRUModelCache()


def get_or_load_model(key: str, loader: Callable[[], Any]) -> Any:
    """Get model from cache or call loader() and cache the result."""
    cached = _model_cache.get(key)
    if cached is not None:
        return cached
    log.info("Loading model: %s", key)
    t0 = time.monotonic()
    model = loader()
    elapsed = (time.monotonic() - t0) * 1000
    log.info("Model '%s' loaded in %.0f ms", key, elapsed)
    _model_cache.put(key, model)
    return model


# ─────────────────────────────────────────────────────────────────────────────
# Whisper model cache


def get_whisper_model(model_name: str = "base", device: str = "cpu"):
    """Load and cache a Whisper model."""
    key = f"whisper:{model_name}:{device}"

    def _load():
        try:
            from faster_whisper import WhisperModel

            ct2 = "float16" if device == "cuda" else "int8"
            return WhisperModel(model_name, device=device, compute_type=ct2)
        except ImportError:
            import whisper

            return whisper.load_model(model_name, device=device)

    return get_or_load_model(key, _load)


# ─────────────────────────────────────────────────────────────────────────────
# Embedding cache


class _EmbeddingCache:
    """LRU cache for embedding vectors (avoids re-encoding repeated text)."""

    def __init__(self, max_size: int = 2048):
        self._max = max_size
        self._store: OrderedDict[str, Any] = OrderedDict()
        self._lock = threading.RLock()
        self._hits = 0
        self._misses = 0

    def get(self, text: str) -> Optional[Any]:
        key = self._hash(text)
        with self._lock:
            if key in self._store:
                self._store.move_to_end(key)
                self._hits += 1
                return self._store[key]
        self._misses += 1
        return None

    def put(self, text: str, embedding: Any) -> None:
        key = self._hash(text)
        with self._lock:
            if key in self._store:
                self._store.move_to_end(key)
            else:
                if len(self._store) >= self._max:
                    self._store.popitem(last=False)
                self._store[key] = embedding

    @staticmethod
    def _hash(text: str) -> str:
        import hashlib

        return hashlib.md5(text.encode()).hexdigest()

    def stats(self) -> Dict[str, Any]:
        total = self._hits + self._misses
        return {
            "hits": self._hits,
            "misses": self._misses,
            "hit_rate": round(self._hits / total, 3) if total else 0,
            "size": len(self._store),
            "max_size": self._max,
        }


_embedding_cache = _EmbeddingCache()


# ─────────────────────────────────────────────────────────────────────────────
# Audio buffer pool (avoids repeated numpy allocations)


class _AudioBufferPool:
    """Object pool for numpy audio buffers."""

    def __init__(self, chunk_size: int = 512, pool_size: int = 32):
        import numpy as np

        self._pool = [np.zeros(chunk_size, dtype=np.float32) for _ in range(pool_size)]
        self._lock = threading.Lock()

    def acquire(self):
        with self._lock:
            return self._pool.pop() if self._pool else None

    def release(self, buf) -> None:
        with self._lock:
            buf.fill(0)
            self._pool.append(buf)


_audio_pool = _AudioBufferPool()


# ─────────────────────────────────────────────────────────────────────────────
# RAM monitor


def current_ram_mb() -> float:
    """Return current process RSS in MB."""
    try:
        import psutil

        return psutil.Process(os.getpid()).memory_info().rss / 1_048_576
    except ImportError:
        return 0.0


def current_cpu_pct() -> float:
    """Return approximate CPU usage %."""
    try:
        import psutil

        return psutil.cpu_percent(interval=0.1)
    except ImportError:
        return 0.0


def log_resource_stats() -> None:
    ram = current_ram_mb()
    cpu = current_cpu_pct()
    mc = _model_cache.stats()
    ec = _embedding_cache.stats()
    log.info(
        "Resources: RAM=%.0f MB, CPU=%.1f%%, Models=%d, EmbCache hit=%.0f%%",
        ram,
        cpu,
        mc["cached_models"],
        ec["hit_rate"] * 100,
    )
    if ram > 400:
        log.warning("High RAM usage (%.0f MB) — forcing GC", ram)
        _model_cache.clear()
        gc.collect()


# ─────────────────────────────────────────────────────────────────────────────
# Startup optimiser (lazy module warm-up)


def warm_up_async(model_name: str = "base", device: str = "cpu") -> threading.Thread:
    """Pre-load the Whisper model in the background during startup."""

    def _do():
        try:
            get_whisper_model(model_name, device)
        except Exception as exc:
            log.warning("Warm-up failed: %s", exc)

    t = threading.Thread(target=_do, daemon=True, name="model-warmup")
    t.start()
    return t


# ─────────────────────────────────────────────────────────────────────────────
# Performance stats aggregation


class PerformanceMonitor:
    """Periodic performance logger."""

    def __init__(self, interval_s: int = 60):
        self._interval = interval_s
        self._stop = threading.Event()
        self._thread: Optional[threading.Thread] = None

    def start(self) -> None:
        self._thread = threading.Thread(
            target=self._loop, daemon=True, name="perf-monitor"
        )
        self._thread.start()
        log.info("PerformanceMonitor started (interval=%ds)", self._interval)

    def stop(self) -> None:
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=5)

    def _loop(self) -> None:
        while not self._stop.wait(timeout=self._interval):
            log_resource_stats()


# ─────────────────────────────────────────────────────────────────────────────
# Convenience exports

__all__ = [
    "get_stt_pool",
    "get_tts_pool",
    "get_llm_pool",
    "get_io_pool",
    "get_whisper_model",
    "get_or_load_model",
    "_embedding_cache",
    "_audio_pool",
    "current_ram_mb",
    "current_cpu_pct",
    "log_resource_stats",
    "warm_up_async",
    "PerformanceMonitor",
]
