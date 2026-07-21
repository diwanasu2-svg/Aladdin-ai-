"""models/mlc_llm.py — Phase 14, Feature 2: MLC-LLM integration.

Provides GPU/NPU acceleration using Vulkan, Metal, or OpenCL for Android
and desktop. Supports runtime model switching and chipset-specific tuning.
"""

from __future__ import annotations

import logging
import os
import platform
import subprocess
import threading
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import Callable, Dict, List, Optional

log = logging.getLogger(__name__)


class MLCBackend(str, Enum):
    VULKAN = "vulkan"
    METAL  = "metal"
    OPENCL = "opencl"
    CUDA   = "cuda"
    CPU    = "cpu"


class Chipset(str, Enum):
    SNAPDRAGON = "snapdragon"
    MEDIATEK   = "mediatek"
    TENSOR     = "tensor"
    GENERIC    = "generic"


MLC_COMPATIBLE_MODELS: Dict[str, Dict] = {
    "llama2-7b-mlc": {
        "artifact": "llama-2-7b-chat-hf-q4f16_1-MLC",
        "hub": "mlc-ai/Llama-2-7b-chat-hf-q4f16_1-MLC",
        "size_gb": 3.8,
    },
    "mistral-7b-mlc": {
        "artifact": "Mistral-7B-Instruct-v0.3-q4f16_1-MLC",
        "hub": "mlc-ai/Mistral-7B-Instruct-v0.3-q4f16_1-MLC",
        "size_gb": 4.0,
    },
    "phi2-mlc": {
        "artifact": "phi-2-q4f16_1-MLC",
        "hub": "mlc-ai/phi-2-q4f16_1-MLC",
        "size_gb": 1.6,
    },
}

CHIPSET_CONFIGS: Dict[Chipset, Dict] = {
    Chipset.SNAPDRAGON: {"backend": MLCBackend.VULKAN, "num_shards": 1, "prefill_chunk": 2048},
    Chipset.MEDIATEK:   {"backend": MLCBackend.OPENCL, "num_shards": 1, "prefill_chunk": 1024},
    Chipset.TENSOR:     {"backend": MLCBackend.VULKAN, "num_shards": 1, "prefill_chunk": 2048},
    Chipset.GENERIC:    {"backend": MLCBackend.CPU,    "num_shards": 1, "prefill_chunk": 512},
}


def _detect_chipset() -> Chipset:
    """Heuristically detect the device chipset."""
    if not os.path.exists("/system/build.prop"):
        return Chipset.GENERIC
    try:
        with open("/system/build.prop") as f:
            props = f.read().lower()
        if "snapdragon" in props or "qualcomm" in props:
            return Chipset.SNAPDRAGON
        if "mediatek" in props or "mt" in props:
            return Chipset.MEDIATEK
        if "tensor" in props or "google" in props:
            return Chipset.TENSOR
    except Exception:
        pass
    return Chipset.GENERIC


def _detect_best_backend() -> MLCBackend:
    system = platform.system()
    if system == "Darwin":
        return MLCBackend.METAL
    if system == "Linux":
        # Check for Vulkan support
        if os.path.exists("/usr/lib/libvulkan.so") or os.path.exists("/system/lib64/libvulkan.so"):
            return MLCBackend.VULKAN
        try:
            result = subprocess.run(["vulkaninfo", "--summary"], capture_output=True, timeout=5)
            if result.returncode == 0:
                return MLCBackend.VULKAN
        except Exception:
            pass
        return MLCBackend.CPU
    return MLCBackend.CPU


@dataclass
class MLCConfig:
    model_key: str = "phi2-mlc"
    model_dir: str = "models/mlc"
    backend: Optional[MLCBackend] = None
    chipset: Optional[Chipset] = None
    max_gen_len: int = 512
    temperature: float = 0.7


class MLCModel:
    """MLC-LLM engine wrapper with GPU/NPU acceleration and runtime model switching."""

    def __init__(self, config: Optional[MLCConfig] = None) -> None:
        self._cfg = config or MLCConfig()
        self._engine = None
        self._lock = threading.Lock()
        self._current_model_key: Optional[str] = None

        self._chipset = self._cfg.chipset or _detect_chipset()
        chipset_cfg = CHIPSET_CONFIGS[self._chipset]
        self._backend = self._cfg.backend or chipset_cfg["backend"]

        log.info("[MLC] Chipset=%s  Backend=%s", self._chipset.value, self._backend.value)

    # ------------------------------------------------------------------
    # Engine lifecycle
    # ------------------------------------------------------------------

    def load(self, model_key: Optional[str] = None) -> None:
        key = model_key or self._cfg.model_key
        info = MLC_COMPATIBLE_MODELS.get(key)
        if not info:
            raise ValueError(f"Unknown MLC model: {key}")

        model_dir = Path(self._cfg.model_dir) / info["artifact"]
        if not model_dir.exists():
            raise FileNotFoundError(
                f"MLC model not found at {model_dir}. "
                "Download with: python -m mlc_llm download --model " + info["hub"]
            )

        with self._lock:
            if self._current_model_key == key and self._engine is not None:
                return

            self._engine = self._create_engine(str(model_dir))
            self._current_model_key = key
            log.info("[MLC] Loaded model=%s backend=%s", key, self._backend.value)

    def _create_engine(self, model_path: str):
        try:
            from mlc_llm import MLCEngine  # type: ignore
            device_str = self._backend.value
            return MLCEngine(model=model_path, device=device_str)
        except ImportError:
            log.warning("[MLC] mlc_llm not installed — falling back to stub engine")
            return _StubMLCEngine(model_path, self._backend)

    def switch_model(self, new_model_key: str) -> None:
        """Swap to a different MLC model at runtime."""
        log.info("[MLC] Switching model: %s → %s", self._current_model_key, new_model_key)
        with self._lock:
            self._engine = None
            self._current_model_key = None
        self.load(new_model_key)

    def unload(self) -> None:
        with self._lock:
            self._engine = None
            self._current_model_key = None
        log.info("[MLC] Engine unloaded")

    # ------------------------------------------------------------------
    # Inference
    # ------------------------------------------------------------------

    def generate(
        self,
        prompt: str,
        max_tokens: Optional[int] = None,
        temperature: Optional[float] = None,
        stream_callback: Optional[Callable[[str], None]] = None,
    ) -> str:
        if self._engine is None:
            self.load()

        max_tok = max_tokens or self._cfg.max_gen_len
        temp = temperature or self._cfg.temperature

        with self._lock:
            if stream_callback:
                return self._stream(prompt, max_tok, temp, stream_callback)
            return self._engine.generate(  # type: ignore[union-attr]
                prompt=prompt,
                max_gen_len=max_tok,
                temperature=temp,
            )

    def _stream(
        self,
        prompt: str,
        max_tokens: int,
        temperature: float,
        callback: Callable[[str], None],
    ) -> str:
        tokens = []
        for token in self._engine.generate_stream(  # type: ignore[union-attr]
            prompt=prompt, max_gen_len=max_tokens, temperature=temperature
        ):
            callback(token)
            tokens.append(token)
        return "".join(tokens)

    def is_available(self) -> bool:
        try:
            import mlc_llm  # type: ignore  # noqa: F401
            return True
        except ImportError:
            return False

    def list_models(self) -> List[str]:
        return list(MLC_COMPATIBLE_MODELS.keys())

    @property
    def backend(self) -> MLCBackend:
        return self._backend

    @property
    def chipset(self) -> Chipset:
        return self._chipset


class _StubMLCEngine:
    """Fallback stub used when mlc_llm is not installed (for testing)."""

    def __init__(self, model_path: str, backend: MLCBackend) -> None:
        self.model_path = model_path
        self.backend = backend

    def generate(self, prompt: str, max_gen_len: int = 256, temperature: float = 0.7) -> str:
        return f"[MLC Stub] Would generate with {self.backend.value} from {self.model_path}"

    def generate_stream(self, prompt: str, max_gen_len: int = 256, temperature: float = 0.7):
        yield "[MLC Stub] "
        yield "streaming "
        yield "response"
