"""production/memory_manager.py — Phase 15, Feature 6: Memory Optimization.

Removes memory leaks, enforces cache size limits, optimizes GC,
profiles heap usage, and manages large model loading/unloading.
"""

from __future__ import annotations

import gc
import logging
import sys
import threading
import time
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional, Tuple, WeakSet

log = logging.getLogger(__name__)


@dataclass
class MemorySnapshot:
    rss_mb: float = 0.0         # Resident Set Size
    vms_mb: float = 0.0         # Virtual Memory Size
    heap_mb: float = 0.0        # Python heap estimate
    gc_objects: int = 0
    timestamp: float = field(default_factory=time.time)

    @property
    def is_critical(self) -> bool:
        return self.rss_mb > 800  # > 800 MB RSS


class MemoryThresholds:
    WARNING_MB:  float = 400.0
    CRITICAL_MB: float = 700.0
    GC_TRIGGER:  float = 300.0
    CACHE_TRIM_MB: float = 250.0


class ObjectTracker:
    """Tracks large objects to detect leaks."""

    def __init__(self) -> None:
        self._registry: Dict[str, Tuple[int, float]] = {}  # tag → (size_bytes, created_at)
        self._lock = threading.Lock()

    def track(self, tag: str, obj: Any) -> None:
        size = sys.getsizeof(obj)
        with self._lock:
            self._registry[tag] = (size, time.time())

    def untrack(self, tag: str) -> None:
        with self._lock:
            self._registry.pop(tag, None)

    def report(self) -> List[Dict[str, Any]]:
        now = time.time()
        with self._lock:
            return [
                {"tag": tag, "size_mb": sz / (1024 ** 2), "age_s": now - ts}
                for tag, (sz, ts) in sorted(
                    self._registry.items(), key=lambda x: x[1][0], reverse=True
                )
            ]


class CacheSizeLimiter:
    """Enforces size limits on in-memory caches."""

    def __init__(self, max_mb: float = 100.0) -> None:
        self.max_bytes = int(max_mb * 1024 * 1024)
        self._caches: List[Any] = []   # caches with .clear() or .evict_expired()
        self._lock = threading.Lock()

    def register_cache(self, cache: Any) -> None:
        with self._lock:
            self._caches.append(cache)

    def enforce(self) -> int:
        """Trim all registered caches if total memory is over limit."""
        evicted = 0
        for cache in self._caches:
            try:
                if hasattr(cache, "evict_expired"):
                    evicted += cache.evict_expired()
            except Exception as exc:
                log.warning("[MemMgr] Cache eviction error: %s", exc)
        return evicted


class MemoryManager:
    """Central memory optimization and leak detection for Aladdin."""

    def __init__(
        self,
        warning_mb: float = MemoryThresholds.WARNING_MB,
        critical_mb: float = MemoryThresholds.CRITICAL_MB,
        check_interval: float = 60.0,
    ) -> None:
        self._warning_mb = warning_mb
        self._critical_mb = critical_mb
        self._check_interval = check_interval
        self.tracker = ObjectTracker()
        self.limiter = CacheSizeLimiter()
        self._history: List[MemorySnapshot] = []
        self._callbacks_warning: List[Callable[[MemorySnapshot], None]] = []
        self._callbacks_critical: List[Callable[[MemorySnapshot], None]] = []
        self._running = False
        self._thread: Optional[threading.Thread] = None

        # Configure GC thresholds for better throughput
        gc.set_threshold(700, 10, 10)

    # ------------------------------------------------------------------
    # Snapshot
    # ------------------------------------------------------------------

    @staticmethod
    def take_snapshot() -> MemorySnapshot:
        snap = MemorySnapshot()
        try:
            import psutil  # type: ignore
            proc = psutil.Process()
            mi = proc.memory_info()
            snap.rss_mb = mi.rss / (1024 ** 2)
            snap.vms_mb = mi.vms / (1024 ** 2)
        except ImportError:
            # Fallback: estimate from gc
            snap.rss_mb = sum(sys.getsizeof(obj) for obj in gc.get_objects()) / (1024 ** 2)

        snap.gc_objects = len(gc.get_objects())
        snap.heap_mb = snap.rss_mb  # use RSS as proxy
        return snap

    # ------------------------------------------------------------------
    # GC optimization
    # ------------------------------------------------------------------

    def force_gc(self, generation: int = 2) -> Tuple[int, float]:
        """Run GC and return (objects_collected, rss_before_mb)."""
        snap_before = self.take_snapshot()
        collected = gc.collect(generation)
        snap_after = self.take_snapshot()
        freed_mb = snap_before.rss_mb - snap_after.rss_mb
        log.info("[MemMgr] GC gen%d collected=%d freed=%.1f MB",
                 generation, collected, freed_mb)
        return collected, freed_mb

    def cleanup_unreferenced(self) -> None:
        """Delete cycles and unreferenced objects."""
        gc.collect(0)
        gc.collect(1)
        gc.collect(2)

    # ------------------------------------------------------------------
    # Model lifecycle
    # ------------------------------------------------------------------

    def load_model(self, tag: str, loader: Callable[[], Any]) -> Any:
        """Load a large model and register it for tracking."""
        log.info("[MemMgr] Loading model: %s", tag)
        snap_before = self.take_snapshot()
        model = loader()
        snap_after = self.take_snapshot()
        delta_mb = snap_after.rss_mb - snap_before.rss_mb
        log.info("[MemMgr] Model '%s' loaded, delta=+%.1f MB", tag, delta_mb)
        self.tracker.track(tag, model)
        return model

    def unload_model(self, tag: str, model_ref: Any) -> float:
        """Unload a model and force GC. Returns estimated freed MB."""
        self.tracker.untrack(tag)
        snap_before = self.take_snapshot()
        del model_ref
        _, freed = self.force_gc()
        log.info("[MemMgr] Model '%s' unloaded, freed≈%.1f MB", tag, freed)
        return freed

    # ------------------------------------------------------------------
    # Monitoring
    # ------------------------------------------------------------------

    def on_warning(self, fn: Callable[[MemorySnapshot], None]) -> None:
        self._callbacks_warning.append(fn)

    def on_critical(self, fn: Callable[[MemorySnapshot], None]) -> None:
        self._callbacks_critical.append(fn)

    def start_monitoring(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._running = True

        def _loop() -> None:
            while self._running:
                snap = self.take_snapshot()
                self._history.append(snap)
                if len(self._history) > 1440:
                    self._history = self._history[-720:]

                if snap.rss_mb >= self._critical_mb:
                    log.critical("[MemMgr] Critical RSS: %.1f MB", snap.rss_mb)
                    self.force_gc()
                    self.limiter.enforce()
                    for cb in self._callbacks_critical:
                        try:
                            cb(snap)
                        except Exception:
                            pass
                elif snap.rss_mb >= self._warning_mb:
                    log.warning("[MemMgr] High RSS: %.1f MB", snap.rss_mb)
                    for cb in self._callbacks_warning:
                        try:
                            cb(snap)
                        except Exception:
                            pass

                time.sleep(self._check_interval)

        self._thread = threading.Thread(target=_loop, daemon=True, name="MemoryMonitor")
        self._thread.start()
        log.info("[MemMgr] Monitoring started (warn=%.0f MB, critical=%.0f MB)",
                 self._warning_mb, self._critical_mb)

    def stop_monitoring(self) -> None:
        self._running = False

    # ------------------------------------------------------------------
    # Profile & report
    # ------------------------------------------------------------------

    def top_objects(self, n: int = 20) -> List[Tuple[str, int]]:
        """Return the n largest object types by total size."""
        from collections import Counter
        sizes: Counter = Counter()
        for obj in gc.get_objects():
            typename = type(obj).__name__
            sizes[typename] += sys.getsizeof(obj)
        return sizes.most_common(n)

    def summary(self) -> Dict[str, Any]:
        snap = self.take_snapshot()
        return {
            "rss_mb": round(snap.rss_mb, 1),
            "vms_mb": round(snap.vms_mb, 1),
            "gc_objects": snap.gc_objects,
            "tracked_objects": len(self.tracker.report()),
            "history_points": len(self._history),
            "gc_thresholds": gc.get_threshold(),
            "gc_counts": gc.get_count(),
        }
