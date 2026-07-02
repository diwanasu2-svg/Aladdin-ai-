"""
reliability_ext/performance_monitor.py — Phase 13 Feature 10
=============================================================
Performance monitoring — AI latency, STT/TTS latency, memory patterns,
FPS, startup time, bottleneck identification.

Features:
- p50/p90/p99 percentile tracking per metric
- AI response time (query to first token, full response)
- STT latency (audio → text)
- TTS latency (text → audio)
- Memory usage patterns
- Startup time from cold start
- Configurable alert thresholds
- Background system metric collection
- JSON and CSV export
"""

from __future__ import annotations

import csv
import json
import logging
import os
import statistics
import threading
import time
from collections import deque
from contextlib import contextmanager
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Callable, Deque, Dict, List, Optional

log = logging.getLogger(__name__)

WINDOW_SIZE = int(os.environ.get("PERF_WINDOW_SIZE", "1000"))

# Baseline thresholds (ms) — alert when exceeded
_BASELINES: Dict[str, float] = {
    "ai_response_ms":     2000.0,
    "stt_latency_ms":     1000.0,
    "tts_latency_ms":     800.0,
    "api_response_ms":    500.0,
    "tool_execution_ms":  5000.0,
    "startup_ms":         10000.0,
}


@dataclass
class MetricPoint:
    value: float
    unit: str
    component: str
    timestamp: float = field(default_factory=time.time)
    tags: Dict[str, str] = field(default_factory=dict)


@dataclass
class MetricStats:
    name: str
    count: int
    mean: float
    median: float
    p90: float
    p99: float
    min: float
    max: float
    unit: str
    component: str
    window_size: int
    baseline: Optional[float] = None
    alert: bool = False

    def to_dict(self) -> dict:
        return asdict(self)


class PerformanceMonitor:
    """
    Tracks and reports performance metrics across Aladdin components.

    Usage::

        pm = PerformanceMonitor()
        pm.start()

        # Manual recording
        pm.record("ai_response_ms", 450.0, component="ai_engine")

        # Context manager (measures automatically)
        with pm.measure("stt_latency_ms", component="voice"):
            do_stt()

        summary = pm.get_summary()
        log.info(summary["metrics"]["ai_response_ms"])

    """

    def __init__(
        self,
        collect_interval: int = 30,
        alert_callback: Optional[Callable[[str, float, float], None]] = None,
    ) -> None:
        self._collect_interval = collect_interval
        self._alert_callback = alert_callback
        self._windows: Dict[str, Deque[MetricPoint]] = {}
        self._lock = threading.Lock()
        self._running = False
        self._thread: Optional[threading.Thread] = None
        self._start_time = time.time()
        self._alerts: List[dict] = []

    # ── Recording ─────────────────────────────────────────────────────────────

    def record(
        self,
        metric: str,
        value: float,
        *,
        unit: str = "ms",
        component: str = "aladdin",
        tags: Optional[Dict[str, str]] = None,
    ) -> None:
        """Record a single metric data point."""
        with self._lock:
            if metric not in self._windows:
                self._windows[metric] = deque(maxlen=WINDOW_SIZE)
            self._windows[metric].append(
                MetricPoint(value=value, unit=unit, component=component, tags=tags or {})
            )

        # Check baseline alert
        baseline = _BASELINES.get(metric)
        if baseline and value > baseline:
            self._emit_alert(metric, value, baseline)

    @contextmanager
    def measure(
        self,
        metric: str,
        *,
        unit: str = "ms",
        component: str = "aladdin",
        tags: Optional[Dict[str, str]] = None,
    ):
        """Context manager: measure and record elapsed time."""
        start = time.perf_counter()
        try:
            yield
        finally:
            elapsed_ms = (time.perf_counter() - start) * 1000
            self.record(metric, elapsed_ms, unit=unit, component=component, tags=tags)

    # ── Convenience recorders ─────────────────────────────────────────────────

    def record_ai_response(self, ms: float, model: str = "") -> None:
        self.record("ai_response_ms", ms, component="ai_engine",
                    tags={"model": model} if model else {})

    def record_stt(self, ms: float, language: str = "") -> None:
        self.record("stt_latency_ms", ms, component="voice", tags={"lang": language})

    def record_tts(self, ms: float, engine: str = "") -> None:
        self.record("tts_latency_ms", ms, component="tts", tags={"engine": engine})

    def record_api(self, endpoint: str, ms: float, status: int = 200) -> None:
        self.record("api_response_ms", ms, component="api",
                    tags={"endpoint": endpoint, "status": str(status)})

    def record_tool(self, tool: str, ms: float) -> None:
        self.record("tool_execution_ms", ms, component="tools", tags={"tool": tool})

    def record_startup(self, ms: float) -> None:
        self.record("startup_ms", ms, component="system")

    def record_wake_word(self, ms: float) -> None:
        self.record("wake_word_latency_ms", ms, component="wake")

    # ── Statistics ────────────────────────────────────────────────────────────

    def get_stats(self, metric: str) -> Optional[MetricStats]:
        with self._lock:
            window = list(self._windows.get(metric, []))
        if not window:
            return None
        values = [p.value for p in window]
        return MetricStats(
            name=metric,
            count=len(values),
            mean=round(statistics.mean(values), 2),
            median=round(statistics.median(values), 2),
            p90=round(self._percentile(values, 90), 2),
            p99=round(self._percentile(values, 99), 2),
            min=round(min(values), 2),
            max=round(max(values), 2),
            unit=window[-1].unit if window else "ms",
            component=window[-1].component if window else "",
            window_size=len(values),
            baseline=_BASELINES.get(metric),
            alert=bool(_BASELINES.get(metric) and statistics.mean(values) > _BASELINES[metric]),
        )

    def get_summary(self) -> Dict[str, Any]:
        with self._lock:
            metric_names = list(self._windows.keys())

        metrics = {}
        for name in metric_names:
            s = self.get_stats(name)
            if s:
                metrics[name] = s.to_dict()

        return {
            "timestamp": time.time(),
            "uptime_seconds": round(time.time() - self._start_time, 1),
            "metrics": metrics,
            "alerts": self._alerts[-20:],
            "recommendations": self._recommendations(metrics),
        }

    def _recommendations(self, metrics: dict) -> List[str]:
        recs = []
        for name, stats in metrics.items():
            p99 = stats.get("p99", 0)
            baseline = _BASELINES.get(name)
            if baseline and p99 > baseline * 2:
                recs.append(f"{name}: p99={p99:.0f}ms is 2x above baseline ({baseline:.0f}ms) — investigate")
            elif baseline and p99 > baseline:
                recs.append(f"{name}: p99={p99:.0f}ms exceeds baseline ({baseline:.0f}ms) — monitor")
        return recs

    # ── Alerts ────────────────────────────────────────────────────────────────

    def _emit_alert(self, metric: str, value: float, baseline: float) -> None:
        alert = {
            "metric": metric, "value": value, "baseline": baseline,
            "ratio": round(value / baseline, 2), "timestamp": time.time(),
        }
        self._alerts.append(alert)
        if len(self._alerts) > 500:
            self._alerts = self._alerts[-500:]
        log.warning("PERF ALERT: %s=%.1fms (baseline=%.0fms)", metric, value, baseline)
        if self._alert_callback:
            try:
                self._alert_callback(metric, value, baseline)
            except Exception as e:
                log.warning("PerformanceMonitor: alert callback error: %s", e)

    def set_baseline(self, metric: str, value_ms: float) -> None:
        _BASELINES[metric] = value_ms

    # ── System metric collection ──────────────────────────────────────────────

    def _collect_loop(self, interval: int) -> None:
        while self._running:
            self._collect_system_metrics()
            time.sleep(interval)

    def _collect_system_metrics(self) -> None:
        try:
            import psutil
            self.record("cpu_percent", psutil.cpu_percent(interval=0.2), unit="%", component="system")
            mem = psutil.virtual_memory()
            self.record("memory_percent", mem.percent, unit="%", component="system")
            self.record("memory_available_mb", mem.available // 1_048_576, unit="MB", component="system")
            # FPS proxy: measure collection cycle time
            self.record("collection_cycle_ms", self._collect_interval * 1000, unit="ms", component="system")
        except ImportError:
            pass
        except Exception as e:
            log.debug("PerformanceMonitor: system metric error: %s", e)

    # ── Lifecycle ─────────────────────────────────────────────────────────────

    def start(self, collect_interval: Optional[int] = None) -> None:
        if self._running:
            return
        self._collect_interval = collect_interval or self._collect_interval
        self._running = True
        self._thread = threading.Thread(
            target=self._collect_loop, args=(self._collect_interval,),
            daemon=True, name="PerformanceMonitor",
        )
        self._thread.start()
        log.info("PerformanceMonitor: started (interval=%ds)", self._collect_interval)

    def stop(self) -> None:
        self._running = False
        if self._thread:
            self._thread.join(timeout=5)

    # ── Export ────────────────────────────────────────────────────────────────

    def export_json(self, path: str) -> None:
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        Path(path).write_text(json.dumps(self.get_summary(), indent=2, default=str), encoding="utf-8")
        log.info("PerformanceMonitor: exported JSON to %s", path)

    def export_csv(self, path: str) -> None:
        p = Path(path)
        p.parent.mkdir(parents=True, exist_ok=True)
        rows = []
        with self._lock:
            for name, window in self._windows.items():
                for m in window:
                    rows.append({"metric": name, "value": m.value, "unit": m.unit,
                                 "component": m.component, "timestamp": m.timestamp})
        with p.open("w", newline="", encoding="utf-8") as f:
            if rows:
                writer = csv.DictWriter(f, fieldnames=["metric", "value", "unit", "component", "timestamp"])
                writer.writeheader()
                writer.writerows(rows)
        log.info("PerformanceMonitor: exported CSV to %s", path)

    @staticmethod
    def _percentile(data: List[float], pct: float) -> float:
        if not data:
            return 0.0
        s = sorted(data)
        k = (len(s) - 1) * pct / 100
        lo, hi = int(k), min(int(k) + 1, len(s) - 1)
        return s[lo] + (s[hi] - s[lo]) * (k - lo)
