"""models/quantization.py — Phase 14, Feature 7 & 8: Quantized Models & GPU Acceleration.

Selects quantization level based on device capabilities and enables
GPU/NPU acceleration with CPU-only fallback.
"""

from __future__ import annotations

import logging
import os
import platform
import subprocess
from dataclasses import dataclass
from enum import Enum
from typing import Dict, Optional, Tuple

log = logging.getLogger(__name__)


class QuantLevel(str, Enum):
    Q4_0   = "Q4_0"    # Smallest — lowest quality
    Q4_K_M = "Q4_K_M"  # Good balance for 4-8 GB RAM
    Q5_K_M = "Q5_K_M"  # Better quality, ~5 GB RAM
    Q6_K   = "Q6_K"    # High quality, ~6 GB RAM
    Q8_0   = "Q8_0"    # Highest quality, ~8 GB RAM
    F16    = "F16"      # Full precision — desktop only


# RAM requirements in GB for each level
QUANT_RAM_REQUIREMENTS: Dict[QuantLevel, float] = {
    QuantLevel.Q4_0:   1.5,
    QuantLevel.Q4_K_M: 3.5,
    QuantLevel.Q5_K_M: 4.5,
    QuantLevel.Q6_K:   5.5,
    QuantLevel.Q8_0:   7.5,
    QuantLevel.F16:    14.0,
}


class AcceleratorBackend(str, Enum):
    CUDA   = "cuda"
    VULKAN = "vulkan"
    METAL  = "metal"
    OPENCL = "opencl"
    CPU    = "cpu"


@dataclass
class DeviceProfile:
    ram_gb: float
    has_cuda: bool = False
    has_vulkan: bool = False
    has_metal: bool = False
    has_opencl: bool = False
    gpu_vram_gb: float = 0.0
    is_android: bool = False


class QuantizationManager:
    """Selects the best quantization level and GPU backend for the current device."""

    def __init__(self) -> None:
        self._profile = self._build_profile()
        log.info(
            "[Quant] Device: %.1f GB RAM  GPU VRAM: %.1f GB  Android: %s",
            self._profile.ram_gb, self._profile.gpu_vram_gb, self._profile.is_android,
        )

    # ------------------------------------------------------------------
    # Profile detection
    # ------------------------------------------------------------------

    @staticmethod
    def _build_profile() -> DeviceProfile:
        try:
            import psutil  # type: ignore
            ram = psutil.virtual_memory().total / (1024 ** 3)
        except ImportError:
            ram = 4.0

        is_android = os.path.exists("/system/build.prop")
        system = platform.system()

        cuda = False
        vulkan = False
        metal = False
        opencl = False
        vram = 0.0

        # CUDA
        try:
            result = subprocess.run(
                ["nvidia-smi", "--query-gpu=memory.total", "--format=csv,noheader,nounits"],
                capture_output=True, timeout=5, text=True,
            )
            if result.returncode == 0:
                cuda = True
                vram = float(result.stdout.strip().split("\n")[0]) / 1024
        except Exception:
            pass

        # Vulkan
        if not cuda:
            vulkan = (
                os.path.exists("/usr/lib/libvulkan.so")
                or os.path.exists("/system/lib64/libvulkan.so")
            )
            if not vulkan:
                try:
                    r = subprocess.run(["vulkaninfo", "--summary"], capture_output=True, timeout=5)
                    vulkan = r.returncode == 0
                except Exception:
                    pass

        # Metal (macOS)
        if system == "Darwin":
            metal = True

        # OpenCL
        try:
            import pyopencl  # type: ignore  # noqa: F401
            opencl = True
        except ImportError:
            opencl = os.path.exists("/usr/lib/libOpenCL.so")

        return DeviceProfile(
            ram_gb=ram,
            has_cuda=cuda,
            has_vulkan=vulkan,
            has_metal=metal,
            has_opencl=opencl,
            gpu_vram_gb=vram,
            is_android=is_android,
        )

    # ------------------------------------------------------------------
    # Quantization selection
    # ------------------------------------------------------------------

    def select_quant_level(self, model_family: str = "generic") -> QuantLevel:
        """Pick best quantization for available RAM."""
        ram = self._profile.ram_gb
        # Walk from highest quality to lowest that fits
        ordered = [QuantLevel.F16, QuantLevel.Q8_0, QuantLevel.Q6_K,
                   QuantLevel.Q5_K_M, QuantLevel.Q4_K_M, QuantLevel.Q4_0]
        for level in ordered:
            if ram >= QUANT_RAM_REQUIREMENTS[level] + 0.5:  # 0.5 GB headroom
                log.info("[Quant] Selected %s for %.1f GB RAM", level.value, ram)
                return level
        return QuantLevel.Q4_0

    def quant_level_for_ram(self, ram_gb: float) -> QuantLevel:
        for level in [QuantLevel.F16, QuantLevel.Q8_0, QuantLevel.Q6_K,
                      QuantLevel.Q5_K_M, QuantLevel.Q4_K_M, QuantLevel.Q4_0]:
            if ram_gb >= QUANT_RAM_REQUIREMENTS[level]:
                return level
        return QuantLevel.Q4_0

    # ------------------------------------------------------------------
    # GPU acceleration
    # ------------------------------------------------------------------

    def select_backend(self) -> AcceleratorBackend:
        if self._profile.has_cuda:
            return AcceleratorBackend.CUDA
        if self._profile.has_metal:
            return AcceleratorBackend.METAL
        if self._profile.has_vulkan:
            return AcceleratorBackend.VULKAN
        if self._profile.has_opencl:
            return AcceleratorBackend.OPENCL
        return AcceleratorBackend.CPU

    def n_gpu_layers(self, model_size_gb: float) -> int:
        """Estimate how many model layers can be offloaded to GPU."""
        backend = self.select_backend()
        if backend == AcceleratorBackend.CPU:
            return 0
        vram = self._profile.gpu_vram_gb
        if vram <= 0:
            # Estimate from system RAM for integrated GPUs
            vram = self._profile.ram_gb * 0.25
        offload_ratio = min(1.0, vram / model_size_gb)
        # Llama-2-7B has ~32 layers; estimate proportionally
        estimated_layers = int(offload_ratio * 35)
        log.info(
            "[Quant] GPU layers: %d  (VRAM=%.1f GB, model=%.1f GB, backend=%s)",
            estimated_layers, vram, model_size_gb, backend.value,
        )
        return estimated_layers

    def llama_cpp_gpu_flags(self) -> Dict[str, int]:
        """Return kwargs to pass to Llama() for GPU acceleration."""
        backend = self.select_backend()
        layers = self.n_gpu_layers(model_size_gb=4.0)  # assume 7B Q4

        flags: Dict[str, int] = {"n_gpu_layers": layers}

        if backend == AcceleratorBackend.VULKAN:
            flags["n_gpu_layers"] = layers
        elif backend == AcceleratorBackend.METAL:
            flags["n_gpu_layers"] = layers
        elif backend == AcceleratorBackend.CUDA:
            flags["n_gpu_layers"] = layers

        return flags

    # ------------------------------------------------------------------
    # Summary
    # ------------------------------------------------------------------

    def summary(self) -> Dict:
        return {
            "ram_gb": self._profile.ram_gb,
            "gpu_vram_gb": self._profile.gpu_vram_gb,
            "is_android": self._profile.is_android,
            "selected_quant": self.select_quant_level().value,
            "selected_backend": self.select_backend().value,
            "n_gpu_layers": self.n_gpu_layers(4.0),
            "available_backends": {
                "cuda": self._profile.has_cuda,
                "vulkan": self._profile.has_vulkan,
                "metal": self._profile.has_metal,
                "opencl": self._profile.has_opencl,
            },
        }
