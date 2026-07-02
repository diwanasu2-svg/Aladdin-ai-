import pickle
"""models/model_cache.py — Phase 14, Feature 6: Model Caching.

LRU cache for prompts, responses, embeddings, and tokenization results
with configurable TTL and persistent cross-session storage.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import joblib
import threading
import time
from collections import OrderedDict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

log = logging.getLogger(__name__)


@dataclass
class CacheEntry:
    key: str
    value: Any
    created_at: float = field(default_factory=time.monotonic)
    ttl: float = 3600.0           # seconds; 0 = immortal
    hit_count: int = 0
    size_bytes: int = 0

    @property
    def is_expired(self) -> bool:
        if self.ttl <= 0:
            return False
        return (time.monotonic() - self.created_at) > self.ttl

    def touch(self) -> None:
        self.hit_count += 1


class LRUCache:
    """Thread-safe LRU cache with TTL support."""

    def __init__(self, max_size: int = 512, default_ttl: float = 3600.0) -> None:
        self._max_size = max_size
        self._default_ttl = default_ttl
        self._data: OrderedDict[str, CacheEntry] = OrderedDict()
        self._lock = threading.RLock()
        self._hits = 0
        self._misses = 0

    def get(self, key: str) -> Optional[Any]:
        with self._lock:
            entry = self._data.get(key)
            if entry is None:
                self._misses += 1
                return None
            if entry.is_expired:
                del self._data[key]
                self._misses += 1
                return None
            self._data.move_to_end(key)
            entry.touch()
            self._hits += 1
            return entry.value

    def set(self, key: str, value: Any, ttl: Optional[float] = None) -> None:
        with self._lock:
            if key in self._data:
                self._data.move_to_end(key)
            self._data[key] = CacheEntry(
                key=key,
                value=value,
                ttl=ttl if ttl is not None else self._default_ttl,
                size_bytes=len(pickle.dumps(value, protocol=4)),
            )
            while len(self._data) > self._max_size:
                self._data.popitem(last=False)

    def delete(self, key: str) -> bool:
        with self._lock:
            return self._data.pop(key, None) is not None

    def clear(self) -> None:
        with self._lock:
            self._data.clear()

    def evict_expired(self) -> int:
        with self._lock:
            expired = [k for k, v in self._data.items() if v.is_expired]
            for k in expired:
                del self._data[k]
            return len(expired)

    @property
    def stats(self) -> Dict[str, Any]:
        with self._lock:
            total_bytes = sum(e.size_bytes for e in self._data.values())
            return {
                "size": len(self._data),
                "max_size": self._max_size,
                "hits": self._hits,
                "misses": self._misses,
                "hit_rate": self._hits / max(1, self._hits + self._misses),
                "total_bytes": total_bytes,
            }


class PersistentCache(LRUCache):
    """Extends LRUCache with disk persistence across sessions."""

    def __init__(
        self,
        cache_dir: str = ".cache/aladdin",
        namespace: str = "default",
        max_size: int = 1024,
        default_ttl: float = 86400.0,  # 24h
    ) -> None:
        super().__init__(max_size=max_size, default_ttl=default_ttl)
        self._cache_file = Path(cache_dir) / f"{namespace}.pkl"
        self._cache_file.parent.mkdir(parents=True, exist_ok=True)
        self._load()

    def _load(self) -> None:
        if not self._cache_file.exists():
            return
        try:
            data: OrderedDict  = joblib.load(self._cache_file)
            with self._lock:
                self._data = data
            evicted = self.evict_expired()
            log.info("[Cache] Loaded %d entries from %s (evicted %d expired)",
                     len(self._data), self._cache_file, evicted)
        except Exception as exc:
            log.warning("[Cache] Failed to load cache: %s", exc)

    def persist(self) -> None:
        try:
            with self._lock:
                data = dict(self._data)
            joblib.dump(data, self._cache_file)
            log.debug("[Cache] Persisted %d entries to %s", len(data), self._cache_file)
        except Exception as exc:
            log.warning("[Cache] Persist failed: %s", exc)

    def set(self, key: str, value: Any, ttl: Optional[float] = None) -> None:
        super().set(key, value, ttl)
        # Async persist (fire-and-forget)
        t = threading.Thread(target=self.persist, daemon=True)
        t.start()


class ModelCache:
    """Central cache facade for all Aladdin caching needs."""

    def __init__(self, cache_dir: str = ".cache/aladdin") -> None:
        # Response cache: prompt → answer
        self._response_cache = PersistentCache(
            cache_dir=cache_dir, namespace="responses",
            max_size=2048, default_ttl=3600.0,
        )
        # Embedding cache
        self._embedding_cache = PersistentCache(
            cache_dir=cache_dir, namespace="embeddings",
            max_size=4096, default_ttl=86400.0,
        )
        # Tokenization cache (in-memory only — fast, disposable)
        self._token_cache = LRUCache(max_size=8192, default_ttl=0)  # immortal

        # Eviction timer
        self._start_eviction_timer()

    # ------------------------------------------------------------------
    # Response cache
    # ------------------------------------------------------------------

    @staticmethod
    def _response_key(prompt: str, model: str, temperature: float) -> str:
        raw = f"{model}|{temperature:.2f}|{prompt}"
        return hashlib.sha256(raw.encode()).hexdigest()

    def get_response(self, prompt: str, model: str = "", temperature: float = 0.7) -> Optional[str]:
        key = self._response_key(prompt, model, temperature)
        return self._response_cache.get(key)

    def set_response(
        self, prompt: str, response: str, model: str = "",
        temperature: float = 0.7, ttl: float = 3600.0,
    ) -> None:
        key = self._response_key(prompt, model, temperature)
        self._response_cache.set(key, response, ttl=ttl)

    # ------------------------------------------------------------------
    # Embedding cache
    # ------------------------------------------------------------------

    @staticmethod
    def _embedding_key(text: str, model: str) -> str:
        return hashlib.sha256(f"{model}|{text}".encode()).hexdigest()

    def get_embedding(self, text: str, model: str = "default") -> Optional[List[float]]:
        return self._embedding_cache.get(self._embedding_key(text, model))

    def set_embedding(self, text: str, embedding: List[float], model: str = "default") -> None:
        self._embedding_cache.set(self._embedding_key(text, model), embedding)

    # ------------------------------------------------------------------
    # Tokenization cache
    # ------------------------------------------------------------------

    def get_tokens(self, text: str) -> Optional[List[int]]:
        return self._token_cache.get(text[:256])  # key on first 256 chars

    def set_tokens(self, text: str, tokens: List[int]) -> None:
        self._token_cache.set(text[:256], tokens)

    # ------------------------------------------------------------------
    # Stats & eviction
    # ------------------------------------------------------------------

    def stats(self) -> Dict[str, Any]:
        return {
            "responses": self._response_cache.stats,
            "embeddings": self._embedding_cache.stats,
            "tokens": self._token_cache.stats,
        }

    def clear_all(self) -> None:
        self._response_cache.clear()
        self._embedding_cache.clear()
        self._token_cache.clear()

    def _start_eviction_timer(self) -> None:
        def _evict_loop() -> None:
            while True:
                time.sleep(300)  # every 5 minutes
                n1 = self._response_cache.evict_expired()
                n2 = self._embedding_cache.evict_expired()
                if n1 or n2:
                    log.debug("[Cache] Evicted %d responses, %d embeddings", n1, n2)

        t = threading.Thread(target=_evict_loop, daemon=True)
        t.start()
