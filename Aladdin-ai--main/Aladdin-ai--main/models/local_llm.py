"""models/local_llm.py — Phase 14, Feature 1: Local LLM via llama.cpp.

Supports GGUF format models (Llama-2, Mistral, Phi, Gemma) with:
- Model download & management
- Device-capability-based model selection
- Full offline mode
- Multiple quantization levels (Q4_K_M, Q5_K_M, Q8_0)
- Android NDK integration shim
"""

from __future__ import annotations

import hashlib
import logging
import os
import platform
import shutil
import subprocess
import threading
import time
import urllib.request
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Callable, Dict, Generator, List, Optional

log = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Enums & constants
# ---------------------------------------------------------------------------

class QuantLevel(str, Enum):
    Q4_0   = "Q4_0"
    Q4_K_M = "Q4_K_M"
    Q5_K_M = "Q5_K_M"
    Q6_K   = "Q6_K"
    Q8_0   = "Q8_0"


KNOWN_MODELS: Dict[str, Dict[str, str]] = {
    "llama2-7b-q4": {
        "filename": "llama-2-7b-chat.Q4_K_M.gguf",
        "url": "https://huggingface.co/TheBloke/Llama-2-7B-Chat-GGUF/resolve/main/llama-2-7b-chat.Q4_K_M.gguf",
        "size_gb": 4.1,
        "quant": QuantLevel.Q4_K_M,
        "family": "llama2",
    },
    "mistral-7b-q4": {
        "filename": "mistral-7b-instruct-v0.2.Q4_K_M.gguf",
        "url": "https://huggingface.co/TheBloke/Mistral-7B-Instruct-v0.2-GGUF/resolve/main/mistral-7b-instruct-v0.2.Q4_K_M.gguf",
        "size_gb": 4.4,
        "quant": QuantLevel.Q4_K_M,
        "family": "mistral",
    },
    "phi3-mini-q4": {
        "filename": "Phi-3-mini-4k-instruct-q4.gguf",
        "url": "https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-gguf/resolve/main/Phi-3-mini-4k-instruct-q4.gguf",
        "size_gb": 2.2,
        "quant": QuantLevel.Q4_K_M,
        "family": "phi3",
    },
    "gemma2b-q4": {
        "filename": "gemma-2b-it-q4_k_m.gguf",
        "url": "https://huggingface.co/lmstudio-ai/gemma-2b-it-GGUF/resolve/main/gemma-2b-it-q4_k_m.gguf",
        "size_gb": 1.5,
        "quant": QuantLevel.Q4_K_M,
        "family": "gemma",
    },
}


# ---------------------------------------------------------------------------
# Device capability detection
# ---------------------------------------------------------------------------

@dataclass
class DeviceCapabilities:
    ram_gb: float
    cpu_cores: int
    is_android: bool
    has_npu: bool = False
    architecture: str = "unknown"

    @classmethod
    def detect(cls) -> "DeviceCapabilities":
        try:
            import psutil  # type: ignore
            ram = psutil.virtual_memory().total / (1024 ** 3)
            cores = psutil.cpu_count(logical=False) or 2
        except ImportError:
            ram = 4.0
            cores = 4

        is_android = os.path.exists("/system/build.prop")
        arch = platform.machine().lower()
        has_npu = is_android and os.path.exists("/dev/qnn_compute")

        return cls(ram_gb=ram, cpu_cores=cores, is_android=is_android,
                   has_npu=has_npu, architecture=arch)

    def recommended_model(self) -> str:
        """Pick the best model that fits this device."""
        if self.ram_gb >= 8:
            return "mistral-7b-q4"
        elif self.ram_gb >= 5:
            return "llama2-7b-q4"
        elif self.ram_gb >= 3:
            return "phi3-mini-q4"
        else:
            return "gemma2b-q4"

    def recommended_threads(self) -> int:
        return max(1, self.cpu_cores - 1)


# ---------------------------------------------------------------------------
# LocalLLM
# ---------------------------------------------------------------------------

class LocalLLM:
    """Runs a GGUF model locally via llama.cpp (llama-cpp-python)."""

    def __init__(
        self,
        model_dir: str = "models/gguf",
        model_key: Optional[str] = None,
        context_length: int = 4096,
        n_gpu_layers: int = 0,
    ) -> None:
        self.model_dir = Path(model_dir)
        self.model_dir.mkdir(parents=True, exist_ok=True)
        self.context_length = context_length
        self.n_gpu_layers = n_gpu_layers
        self._llm = None
        self._lock = threading.Lock()
        self._loaded_model_key: Optional[str] = None

        caps = DeviceCapabilities.detect()
        self._caps = caps
        self._model_key = model_key or caps.recommended_model()
        self._threads = caps.recommended_threads()
        log.info("[LocalLLM] Device: %.1f GB RAM, %d cores, Android=%s",
                 caps.ram_gb, caps.cpu_cores, caps.is_android)

    # ------------------------------------------------------------------
    # Model management
    # ------------------------------------------------------------------

    def model_path(self, model_key: Optional[str] = None) -> Path:
        key = model_key or self._model_key
        info = KNOWN_MODELS.get(key, {})
        filename = info.get("filename", f"{key}.gguf")
        return self.model_dir / filename

    def is_downloaded(self, model_key: Optional[str] = None) -> bool:
        return self.model_path(model_key).exists()

    def download_model(
        self,
        model_key: Optional[str] = None,
        progress_callback: Optional[Callable[[float], None]] = None,
    ) -> Path:
        """Download the GGUF model file with progress reporting."""
        key = model_key or self._model_key
        info = KNOWN_MODELS.get(key)
        if not info:
            raise ValueError(f"Unknown model key: {key}")

        dest = self.model_path(key)
        if dest.exists():
            log.info("[LocalLLM] Model already present: %s", dest)
            return dest

        url = info["url"]
        tmp = dest.with_suffix(".part")
        log.info("[LocalLLM] Downloading %s → %s", url, dest)

        def _reporthook(block_num: int, block_size: int, total_size: int) -> None:
            if total_size > 0 and progress_callback:
                pct = min(1.0, block_num * block_size / total_size)
                progress_callback(pct)

        try:
            urllib.request.urlretrieve(url, tmp, reporthook=_reporthook)
            tmp.rename(dest)
            log.info("[LocalLLM] Download complete: %s", dest)
        except Exception as exc:
            tmp.unlink(missing_ok=True)
            raise RuntimeError(f"Download failed for {key}: {exc}") from exc

        return dest

    def list_downloaded(self) -> List[str]:
        """Return model keys that are already on disk."""
        return [k for k in KNOWN_MODELS if self.is_downloaded(k)]

    def delete_model(self, model_key: str) -> None:
        path = self.model_path(model_key)
        if path.exists():
            path.unlink()
            log.info("[LocalLLM] Deleted model: %s", path)

    # ------------------------------------------------------------------
    # Load / unload
    # ------------------------------------------------------------------

    def load(self, model_key: Optional[str] = None) -> None:
        """Load model into memory."""
        key = model_key or self._model_key
        path = self.model_path(key)
        if not path.exists():
            raise FileNotFoundError(
                f"Model not found: {path}. Call download_model() first."
            )

        with self._lock:
            if self._loaded_model_key == key and self._llm is not None:
                return  # already loaded

            try:
                from llama_cpp import Llama  # type: ignore
            except ImportError:
                raise ImportError(
                    "llama-cpp-python is not installed. "
                    "Run: pip install llama-cpp-python"
                )

            log.info("[LocalLLM] Loading %s (threads=%d, gpu_layers=%d)",
                     path, self._threads, self.n_gpu_layers)
            self._llm = Llama(
                model_path=str(path),
                n_ctx=self.context_length,
                n_threads=self._threads,
                n_gpu_layers=self.n_gpu_layers,
                verbose=False,
            )
            self._loaded_model_key = key
            log.info("[LocalLLM] Model loaded: %s", key)

    def unload(self) -> None:
        with self._lock:
            self._llm = None
            self._loaded_model_key = None
        log.info("[LocalLLM] Model unloaded")

    # ------------------------------------------------------------------
    # Inference
    # ------------------------------------------------------------------

    def generate(
        self,
        prompt: str,
        max_tokens: int = 512,
        temperature: float = 0.7,
        stop: Optional[List[str]] = None,
        stream_callback: Optional[Callable[[str], None]] = None,
    ) -> str:
        """Run inference. Auto-loads if needed."""
        if self._llm is None:
            self.load()

        kwargs = dict(
            prompt=prompt,
            max_tokens=max_tokens,
            temperature=temperature,
            stop=stop or ["</s>", "[/INST]", "[INST]"],
            echo=False,
        )

        with self._lock:
            if stream_callback:
                return self._stream(kwargs, stream_callback)
            else:
                result = self._llm(**kwargs)  # type: ignore[operator]
                return result["choices"][0]["text"].strip()

    def _stream(self, kwargs: dict, callback: Callable[[str], None]) -> str:
        kwargs["stream"] = True
        tokens = []
        for chunk in self._llm(**kwargs):  # type: ignore[operator]
            token = chunk["choices"][0]["text"]
            if token:
                callback(token)
                tokens.append(token)
        return "".join(tokens).strip()

    def chat(
        self,
        messages: List[Dict],
        max_tokens: int = 512,
        temperature: float = 0.7,
        stream_callback: Optional[Callable[[str], None]] = None,
    ) -> str:
        """OpenAI-style chat interface."""
        if self._llm is None:
            self.load()

        with self._lock:
            result = self._llm.create_chat_completion(  # type: ignore[union-attr]
                messages=messages,
                max_tokens=max_tokens,
                temperature=temperature,
                stream=stream_callback is not None,
            )
            if stream_callback:
                tokens = []
                for chunk in result:
                    delta = chunk["choices"][0].get("delta", {})
                    token = delta.get("content", "")
                    if token:
                        callback(token)
                        tokens.append(token)
                return "".join(tokens).strip()
            else:
                return result["choices"][0]["message"]["content"].strip()

    def is_available(self) -> bool:
        try:
            import llama_cpp  # type: ignore  # noqa: F401
            return True
        except ImportError:
            return False

    # ------------------------------------------------------------------
    # Android NDK shim
    # ------------------------------------------------------------------

    @staticmethod
    def build_android_ndk(
        ndk_path: str,
        output_dir: str = "android_llama",
        abi: str = "arm64-v8a",
    ) -> bool:
        """Invoke cmake + NDK to build llama.cpp for Android."""
        cmake = shutil.which("cmake")
        if not cmake:
            log.error("[LocalLLM] cmake not found — cannot build NDK lib")
            return False

        out = Path(output_dir)
        out.mkdir(parents=True, exist_ok=True)
        cmd = [
            cmake,
            f"-DCMAKE_TOOLCHAIN_FILE={ndk_path}/build/cmake/android.toolchain.cmake",
            f"-DANDROID_ABI={abi}",
            "-DANDROID_PLATFORM=android-28",
            "-DLLAMA_ANDROID=ON",
            "-DLLAMA_VULKAN=ON",
            "-B", str(out),
            ".",
        ]
        try:
            subprocess.run(cmd, check=True, timeout=300)
            subprocess.run(["cmake", "--build", str(out), "--config", "Release"],
                           check=True, timeout=600)
            log.info("[LocalLLM] NDK build succeeded → %s", out)
            return True
        except Exception as exc:
            log.error("[LocalLLM] NDK build failed: %s", exc)
            return False
