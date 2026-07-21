"""
reliability_ext/diagnostics.py — Phase 13 Feature 5
====================================================
Comprehensive diagnostics — system info, error pattern analysis,
performance bottleneck identification, debug report generation.

Features:
- Full system info collection
- Error/exception pattern analysis
- Performance bottleneck identification
- JSON debug reports
- Health report export
- Log analysis with filtering
- Performance profiling integration
"""

from __future__ import annotations

import json
import logging
import os
import platform
import sys
import threading
import time
import traceback
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

log = logging.getLogger(__name__)


@dataclass
class DiagnosticReport:
    report_id: str
    generated_at: float = field(default_factory=time.time)
    system_info: Dict[str, Any] = field(default_factory=dict)
    error_patterns: Dict[str, Any] = field(default_factory=dict)
    performance_summary: Dict[str, Any] = field(default_factory=dict)
    health_snapshot: Dict[str, Any] = field(default_factory=dict)
    log_analysis: Dict[str, Any] = field(default_factory=dict)
    recommendations: List[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        d = asdict(self)
        d["generated_at_iso"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(self.generated_at))
        return d


class Diagnostics:
    """
    System diagnostics and debug report generation.

    Usage::

        diag = Diagnostics()
        diag.record_error("ValueError", "bad input", traceback_str)
        report = diag.generate_report()
        diag.export_report(report, "debug_report.json")
    """

    def __init__(self, log_dir: str = "logs") -> None:
        self._log_dir = Path(log_dir)
        self._error_log: List[dict] = []
        self._lock = threading.Lock()
        self._performance_monitor = None
        self._health_monitor = None

    def attach(self, *, performance_monitor=None, health_monitor=None) -> None:
        """Attach performance and health monitors for richer reports."""
        self._performance_monitor = performance_monitor
        self._health_monitor = health_monitor

    # ── Error recording ───────────────────────────────────────────────────────

    def record_error(
        self,
        error_type: str,
        message: str,
        stack_trace: str = "",
        component: str = "",
    ) -> None:
        with self._lock:
            self._error_log.append({
                "timestamp": time.time(),
                "type": error_type,
                "message": message,
                "stack_trace": stack_trace,
                "component": component,
            })
            if len(self._error_log) > 5000:
                self._error_log = self._error_log[-5000:]

    def record_exception(self, exc: Exception, component: str = "") -> None:
        self.record_error(
            error_type=type(exc).__name__,
            message=str(exc),
            stack_trace=traceback.format_exc(),
            component=component,
        )

    # ── System info ───────────────────────────────────────────────────────────

    def collect_system_info(self) -> dict:
        info: Dict[str, Any] = {
            "platform": platform.platform(),
            "os": platform.system(),
            "os_version": platform.version(),
            "architecture": platform.machine(),
            "python_version": sys.version,
            "python_executable": sys.executable,
            "pid": os.getpid(),
            "cwd": os.getcwd(),
            "timestamp": time.time(),
        }
        try:
            import psutil
            info["cpu_count_logical"] = psutil.cpu_count(logical=True)
            info["cpu_count_physical"] = psutil.cpu_count(logical=False)
            info["cpu_freq_mhz"] = getattr(psutil.cpu_freq(), "current", None)
            mem = psutil.virtual_memory()
            info["memory_total_gb"] = round(mem.total / 1e9, 2)
            info["memory_available_gb"] = round(mem.available / 1e9, 2)
            info["memory_percent"] = mem.percent
            disk = psutil.disk_usage("/")
            info["disk_total_gb"] = round(disk.total / 1e9, 2)
            info["disk_free_gb"] = round(disk.free / 1e9, 2)
            info["disk_percent"] = disk.percent
            info["boot_time"] = psutil.boot_time()
            info["uptime_hours"] = round((time.time() - psutil.boot_time()) / 3600, 1)

            # Open file descriptors
            proc = psutil.Process()
            info["open_files"] = len(proc.open_files())
            info["threads"] = proc.num_threads()
            info["connections"] = len(proc.connections())
        except ImportError:
            info["psutil"] = "not installed"
        except Exception as e:
            info["psutil_error"] = str(e)

        # Python packages
        try:
            import importlib.metadata as importlib_metadata
            pkgs = {}
            for dist in importlib_metadata.distributions():
                pkgs[dist.metadata["Name"]] = dist.metadata["Version"]
            info["installed_packages_count"] = len(pkgs)
        except Exception:
            pass

        return info

    # ── Error analysis ────────────────────────────────────────────────────────

    def analyze_errors(self, since_seconds: int = 3600) -> dict:
        cutoff = time.time() - since_seconds
        with self._lock:
            recent = [e for e in self._error_log if e["timestamp"] >= cutoff]

        type_counts = Counter(e["type"] for e in recent)
        comp_counts = Counter(e["component"] for e in recent if e["component"])
        error_rate = len(recent) / max(since_seconds / 60, 1)

        return {
            "window_seconds": since_seconds,
            "total_errors": len(recent),
            "error_rate_per_minute": round(error_rate, 2),
            "by_type": dict(type_counts.most_common(10)),
            "by_component": dict(comp_counts.most_common(10)),
            "most_recent": recent[-5:] if recent else [],
            "most_frequent_message": Counter(e["message"][:100] for e in recent).most_common(3),
        }

    # ── Performance bottleneck analysis ──────────────────────────────────────

    def identify_bottlenecks(self) -> List[dict]:
        bottlenecks = []
        if self._performance_monitor:
            summary = self._performance_monitor.get_summary()
            for metric_name, stats in summary.get("metrics", {}).items():
                p99 = stats.get("p99", 0)
                if "latency" in metric_name and p99 > 2000:
                    bottlenecks.append({
                        "metric": metric_name,
                        "p99_ms": p99,
                        "severity": "critical" if p99 > 5000 else "warning",
                        "recommendation": f"Optimize {metric_name} — p99={p99:.0f}ms exceeds 2s threshold",
                    })
                elif "cpu" in metric_name and stats.get("max", 0) > 90:
                    bottlenecks.append({
                        "metric": metric_name,
                        "max_pct": stats.get("max"),
                        "severity": "warning",
                        "recommendation": "High CPU usage detected — consider async processing or horizontal scaling",
                    })
        return bottlenecks

    # ── Log analysis ─────────────────────────────────────────────────────────

    def analyze_logs(
        self,
        log_file: Optional[str] = None,
        level_filter: Optional[str] = None,
        keyword: Optional[str] = None,
        limit: int = 100,
    ) -> dict:
        log_path = Path(log_file) if log_file else self._log_dir / "aladdin.log"
        if not log_path.exists():
            return {"error": f"Log file not found: {log_path}", "entries": []}

        entries = []
        level_counts: Counter = Counter()
        try:
            with log_path.open("r", encoding="utf-8", errors="replace") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    for lvl in ("DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"):
                        if lvl in line:
                            level_counts[lvl] += 1
                            break
                    if level_filter and level_filter.upper() not in line:
                        continue
                    if keyword and keyword.lower() not in line.lower():
                        continue
                    entries.append(line)
        except Exception as e:
            return {"error": str(e), "entries": []}

        return {
            "log_file": str(log_path),
            "total_lines_analyzed": sum(level_counts.values()),
            "level_distribution": dict(level_counts),
            "matched_entries": entries[-limit:],
            "match_count": len(entries),
        }

    # ── Report generation ─────────────────────────────────────────────────────

    def generate_report(self, report_id: Optional[str] = None) -> DiagnosticReport:
        """Generate a full diagnostic report."""
        import uuid
        rid = report_id or str(uuid.uuid4())[:8]
        log.info("Diagnostics: generating report %s", rid)

        system_info = self.collect_system_info()
        error_patterns = self.analyze_errors()
        bottlenecks = self.identify_bottlenecks()

        recommendations = []
        if system_info.get("memory_percent", 0) > 80:
            recommendations.append("Memory usage above 80% — consider increasing RAM or reducing model size")
        if system_info.get("disk_percent", 0) > 85:
            recommendations.append("Disk usage above 85% — clean old logs and model caches")
        if error_patterns.get("error_rate_per_minute", 0) > 5:
            recommendations.append("High error rate — review error logs for recurring issues")
        for b in bottlenecks:
            recommendations.append(b.get("recommendation", ""))

        health_snapshot = {}
        if self._health_monitor:
            try:
                health_snapshot = self._health_monitor.get_status_response()
            except Exception:
                pass

        perf_summary = {}
        if self._performance_monitor:
            try:
                perf_summary = self._performance_monitor.get_summary()
            except Exception:
                pass

        return DiagnosticReport(
            report_id=rid,
            system_info=system_info,
            error_patterns=error_patterns,
            performance_summary=perf_summary,
            health_snapshot=health_snapshot,
            recommendations=recommendations,
        )

    def export_report(self, report: DiagnosticReport, path: str) -> str:
        """Export a diagnostic report to JSON."""
        p = Path(path)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps(report.to_dict(), indent=2, default=str), encoding="utf-8")
        log.info("Diagnostics: report exported to %s", p)
        return str(p)

    def export_health_report(self, path: str) -> str:
        """Quick health-only export."""
        report = self.generate_report()
        return self.export_report(report, path)
