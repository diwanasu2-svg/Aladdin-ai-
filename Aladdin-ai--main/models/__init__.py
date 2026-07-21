"""Phase 14 — AI Models package.

Exports the central LLM manager and all subsystems so callers can do:

    from models import LLMManager
    from models.provider_switcher import ProviderSwitcher
"""

from .llm_manager import LLMManager
from .provider_switcher import ProviderSwitcher
from .streaming_manager import StreamingManager
from .fallback_chain import FallbackChain
from .model_cache import ModelCache
from .prompt_optimizer import PromptOptimizer
from .token_optimizer import TokenOptimizer
from .gpu_accelerator import GPUAccelerator
from .quantization import QuantizationManager
from .local_llm import LocalLLM
from .mlc_llm import MLCModel

__all__ = [
    "LLMManager",
    "ProviderSwitcher",
    "StreamingManager",
    "FallbackChain",
    "ModelCache",
    "PromptOptimizer",
    "TokenOptimizer",
    "GPUAccelerator",
    "QuantizationManager",
    "LocalLLM",
    "MLCModel",
]
