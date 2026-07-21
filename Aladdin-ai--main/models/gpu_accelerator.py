"""models/gpu_accelerator.py — Phase 14, Feature 8: GPU Acceleration Manager.

Detects device capabilities, enables GPU/NPU acceleration, manages GPU
memory, and provides a CPU-only fallback path.
"""

from __future__ import annotations

import logging
import os
import platform
import subprocess
import threading
import time
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional

log = logging.getLogger(__name__)


@dataclass
class GPUInfo:
    name: str = "Unknown"
    vram_mb: int = 0
    driver_version: str = ""
    backend: str = "cpu"
    utilization_pct: float = 0.0
    temp_celsius: float = 0.0


@dataclass
class GPUMemoryStats:
    total_mb: int = 0
    used_mb: int = 0
    free_mb: int = 0

    @property
    def utilization(self) -> float:
        if self.total_mb == 0:
            return 0.0
        return self.used_mb / self.total_mb


class GPUAccelerator:
    """Central GPU/NPU acceleration manager with health monitoring."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._gpu_info: Optional[GPUInfo] = None
        self._enabled: bool = False
        self._backend: str = "cpu"
        self._monitor_thread: Optional[threading.Thread] = None
        self._memory_stats: GPUMemoryStats = GPUMemoryStats()
        self._oom_callbacks: List[Callable] = []

        self._detect()

    # ------------------------------------------------------------------
    # Detection
    # ------------------------------------------------------------------

    def _detect(self) -> None:
        """Auto-detect available GPU backends."""
        if self._try_cuda():
            return
        if self._try_vulkan():
            return
        if self._try_metal():
            return
        if self._try_opencl():
            return
        log.info("[GPU] No GPU acceleration found — CPU mode")
        self._backend = "cpu"
        self._enabled = False

    def _try_cuda(self) -> bool:
        try:
            result = subprocess.run(
                ["nvidia-smi", "--query-gpu=name,memory.total,driver_version",
                 "--format=csv,noheader"],
                capture_output=True, timeout=5, text=True,
            )
            if result.returncode != 0:
                return False
            parts = result.stdout.strip().split(",")
            if len(parts) >= 3:
                self._gpu_info = GPUInfo(
                    name=parts[0].strip(),
                    vram_mb=int(parts[1].strip().replace(" MiB", "")),
                    driver_version=parts[2].strip(),
                    backend="cuda",
                )
            self._backend = "cuda"
            self._enabled = True
            log.info("[GPU] CUDA detected: %s  VRAM=%d MB",
                     self._gpu_info.name if self._gpu_info else "?",
                     self._gpu_info.vram_mb if self._gpu_info else 0)
            return True
        except Exception:
            return False

    def _try_vulkan(self) -> bool:
        paths = ["/usr/lib/libvulkan.so", "/system/lib64/libvulkan.so",
                 "/usr/local/lib/libvulkan.so"]
        if not any(os.path.exists(p) for p in paths):
            try:
                r = subprocess.run(["vulkaninfo", "--summary"],
                                   capture_output=True, timeout=5)
                if r.returncode != 0:
                    return False
            except FileNotFoundError:
                return False

        self._backend = "vulkan"
        self._enabled = True
        self._gpu_info = GPUInfo(name="Vulkan-capable GPU", backend="vulkan")
        log.info("[GPU] Vulkan detected")
        return True

    def _try_metal(self) -> bool:
        if platform.system() != "Darwin":
            return False
        try:
            result = subprocess.run(
                ["system_profiler", "SPDisplaysDataType"],
                capture_output=True, timeout=10, text=True,
            )
            if result.returncode == 0 and "Metal" in result.stdout:
                self._backend = "metal"
                self._enabled = True
                self._gpu_info = GPUInfo(name="Apple Metal GPU", backend="metal")
                log.info("[GPU] Metal detected")
                return True
        except Exception:
            pass
        return False

    def _try_opencl(self) -> bool:
        try:
            import pyopencl as cl  # type: ignore
            platforms = cl.get_platforms()
            if platforms:
                devices = platforms[0].get_devices()
                if devices:
                    self._backend = "opencl"
                    self._enabled = True
                    self._gpu_info = GPUInfo(
                        name=devices[0].name,
                        vram_mb=int(devices[0].global_mem_size / (1024 ** 2)),
                        backend="opencl",
                    )
                    log.info("[GPU] OpenCL detected: %s", self._gpu_info.name)
                    return True
        except Exception:
            pass
        return False

    # ------------------------------------------------------------------
    # Memory management
    # ------------------------------------------------------------------

    def memory_stats(self) -> GPUMemoryStats:
        """Query current GPU memory usage."""
        if self._backend == "cuda":
            try:
                result = subprocess.run(
                    ["nvidia-smi", "--query-gpu=memory.used,memory.free,memory.total",
                     "--format=csv,noheader,nounits"],
                    capture_output=True, timeout=5, text=True,
                )
                if result.returncode == 0:
                    parts = result.stdout.strip().split(",")
                    used, free, total = int(parts[0]), int(parts[1]), int(parts[2])
                    self._memory_stats = GPUMemoryStats(total_mb=total, used_mb=used, free_mb=free)
            except Exception:
                pass
        return self._memory_stats

    def free_memory(self) -> None:
        """Attempt to free GPU memory caches."""
        if self._backend == "cuda":
            try:
                import torch  # type: ignore
                torch.cuda.empty_cache()
                log.debug("[GPU] CUDA cache cleared")
            except ImportError:
                pass

    def register_oom_callback(self, callback: Callable) -> None:
        """Called when GPU OOM is detected — allows fallback to CPU."""
        self._oom_callbacks.append(callback)

    def _trigger_oom(self) -> None:
        for cb in self._oom_callbacks:
            try:
                cb()
            except Exception as exc:
                log.warning("[GPU] OOM callback error: %s", exc)

    # ------------------------------------------------------------------
    # Monitoring
    # ------------------------------------------------------------------

    def start_monitoring(self, interval: float = 10.0) -> None:
        """Background thread that monitors GPU health."""
        if self._monitor_thread and self._monitor_thread.is_alive():
            return

        def _monitor() -> None:
            while self._enabled:
                stats = self.memory_stats()
                if stats.utilization > 0.95:
                    log.warning("[GPU] Memory critical: %d/%d MB used (%.0f%%)",
                                stats.used_mb, stats.total_mb, stats.utilization * 100)
                    self._trigger_oom()
                time.sleep(interval)

        self._monitor_thread = threading.Thread(target=_monitor, daemon=True)
        self._monitor_thread.start()

    # ------------------------------------------------------------------
    # Inference wrapper
    # ------------------------------------------------------------------

    def run_with_fallback(
        self,
        gpu_fn: Callable[[], Any],
        cpu_fn: Callable[[], Any],
    ) -> Any:
        """Try GPU inference; fall back to CPU on any error."""
        if not self._enabled:
            return cpu_fn()
        try:
            return gpu_fn()
        except Exception as exc:
            err_str = str(exc).lower()
            if "out of memory" in err_str or "oom" in err_str:
                self._trigger_oom()
                self.free_memory()
                log.warning("[GPU] OOM — falling back to CPU")
            else:
                log.warning("[GPU] GPU inference failed (%s) — falling back to CPU", exc)
            return cpu_fn()

    # ------------------------------------------------------------------
    # Properties
    # ------------------------------------------------------------------

    @property
    def is_enabled(self) -> bool:
        return self._enabled

    @property
    def backend(self) -> str:
        return self._backend

    @property
    def info(self) -> Optional[GPUInfo]:
        return self._gpu_info

    def summary(self) -> Dict[str, Any]:
        stats = self.memory_stats()
        return {
            "enabled": self._enabled,
            "backend": self._backend,
            "gpu_name": self._gpu_info.name if self._gpu_info else "none",
            "vram_total_mb": stats.total_mb,
            "vram_used_mb": stats.used_mb,
            "vram_free_mb": stats.free_mb,
            "utilization_pct": round(stats.utilization * 100, 1),
        }
