"""models/llm_manager.py — Phase 14, Feature 1-10: Central LLM Manager.

Wires together ProviderSwitcher, FallbackChain, StreamingManager,
ModelCache, PromptOptimizer, TokenOptimizer, GPUAccelerator, and
QuantizationManager into a single unified interface.
"""

from __future__ import annotations

import logging
import threading
import time
from typing import Any, Callable, Dict, List, Optional, Tuple

from .fallback_chain import FallbackChain, FallbackLevel, build_default_chain
from .gpu_accelerator import GPUAccelerator
from .model_cache import ModelCache
from .prompt_optimizer import Message, PromptOptimizer
from .provider_switcher import ProviderName, ProviderSwitcher
from .quantization import QuantizationManager
from .streaming_manager import StreamProtocol, StreamingManager, StreamingSession
from .token_optimizer import TokenBudget, TokenOptimizer

log = logging.getLogger(__name__)


class LLMManager:
    """Central AI orchestration layer for Aladdin.

    Usage::

        manager = LLMManager(system_prompt="You are Aladdin...")
        manager.register_ollama(ollama_client)
        manager.register_local_llm(local_llm)

        # Simple call
        reply = manager.chat("What is the weather?")

        # Streaming call
        session = manager.stream("Tell me a story", ui_callback=lambda c: log.info(c.text, end=""))
        session.wait()
        full_text = session.full_text
    """

    def __init__(
        self,
        system_prompt: str = "You are Aladdin, a helpful AI assistant.",
        model_family: str = "ollama",
        max_context_tokens: int = 4096,
        cache_dir: str = ".cache/aladdin",
        enable_gpu: bool = True,
        enable_cache: bool = True,
    ) -> None:
        self.system_prompt = system_prompt

        # Sub-systems
        self.provider_switcher = ProviderSwitcher()
        self.fallback_chain = FallbackChain()
        self.streaming_manager = StreamingManager()
        self.cache = ModelCache(cache_dir=cache_dir) if enable_cache else None
        self.prompt_optimizer = PromptOptimizer(
            model_family=model_family,
            max_context_tokens=max_context_tokens,
            system_prompt=system_prompt,
        )
        self.token_optimizer = TokenOptimizer(
            max_context_tokens=max_context_tokens,
            llm_fn=self._summarize_fn,
        )
        self.gpu = GPUAccelerator() if enable_gpu else None
        self.quant = QuantizationManager()

        if enable_gpu and self.gpu:
            self.gpu.start_monitoring()

        log.info(
            "[LLMManager] Initialized | family=%s | gpu=%s | backend=%s | quant=%s",
            model_family,
            enable_gpu,
            self.gpu.backend if self.gpu else "cpu",
            self.quant.select_quant_level().value,
        )

    # ------------------------------------------------------------------
    # Provider registration helpers
    # ------------------------------------------------------------------

    def register_ollama(self, client: Any) -> None:
        """Register an OllamaClient from llm.py."""
        def _handler(prompt: str, stream_cb: Optional[Callable] = None) -> str:
            return client.chat(prompt, stream_callback=stream_cb)

        self.provider_switcher.register_handler(
            ProviderName.OLLAMA,
            handler=_handler,
            availability_check=client.is_available,
        )
        self.fallback_chain.add(
            "ollama", _handler, FallbackLevel.PRIMARY,
            availability_check=client.is_available,
        )
        log.info("[LLMManager] Registered Ollama provider")

    def register_local_llm(self, local_llm: Any) -> None:
        """Register a LocalLLM instance."""
        def _handler(prompt: str, stream_cb: Optional[Callable] = None) -> str:
            return local_llm.generate(prompt, stream_callback=stream_cb)

        self.provider_switcher.register_handler(
            ProviderName.LOCAL_LLM,
            handler=_handler,
            availability_check=local_llm.is_available,
        )
        self.fallback_chain.add(
            "local_llm", _handler, FallbackLevel.SECONDARY,
            availability_check=local_llm.is_available,
            timeout=120.0,
        )
        log.info("[LLMManager] Registered LocalLLM provider")

    def register_mlc(self, mlc_model: Any) -> None:
        """Register an MLCModel instance."""
        def _handler(prompt: str, stream_cb: Optional[Callable] = None) -> str:
            return mlc_model.generate(prompt, stream_callback=stream_cb)

        self.provider_switcher.register_handler(
            ProviderName.MLC_LLM,
            handler=_handler,
            availability_check=mlc_model.is_available,
        )
        self.fallback_chain.add(
            "mlc_llm", _handler, FallbackLevel.TERTIARY,
            availability_check=mlc_model.is_available,
            timeout=90.0,
        )
        log.info("[LLMManager] Registered MLC-LLM provider")

    def register_openai(
        self,
        api_key: str,
        model: str = "gpt-4o-mini",
    ) -> None:
        """Register OpenAI provider."""
        def _handler(prompt: str, stream_cb: Optional[Callable] = None) -> str:
            try:
                import openai  # type: ignore
                client = openai.OpenAI(api_key=api_key)
                if stream_cb:
                    result = []
                    with client.chat.completions.create(
                        model=model,
                        messages=[
                            {"role": "system", "content": self.system_prompt},
                            {"role": "user", "content": prompt},
                        ],
                        stream=True,
                    ) as stream:
                        for chunk in stream:
                            token = (chunk.choices[0].delta.content or "")
                            if token:
                                stream_cb(token)
                                result.append(token)
                    return "".join(result)
                else:
                    resp = client.chat.completions.create(
                        model=model,
                        messages=[
                            {"role": "system", "content": self.system_prompt},
                            {"role": "user", "content": prompt},
                        ],
                    )
                    return resp.choices[0].message.content or ""
            except Exception as exc:
                raise RuntimeError(f"OpenAI error: {exc}") from exc

        def _avail() -> bool:
            try:
                import openai  # type: ignore  # noqa: F401
                return bool(api_key)
            except ImportError:
                return False

        self.provider_switcher.register_handler(ProviderName.OPENAI, _handler, _avail)
        self.fallback_chain.add("openai", _handler, FallbackLevel.PRIMARY,
                                availability_check=_avail)
        log.info("[LLMManager] Registered OpenAI provider (model=%s)", model)

    def register_rule_based(self, handler: Callable[[str], str]) -> None:
        """Register a rule-based ultimate fallback."""
        def _handler(prompt: str, stream_cb: Optional[Callable] = None) -> str:
            return handler(prompt)

        self.fallback_chain.add(
            "rule_based", _handler, FallbackLevel.RULE_BASED,
            retry_attempts=1, cooldown=0.0,
        )

    # ------------------------------------------------------------------
    # Main inference interface
    # ------------------------------------------------------------------

    def chat(
        self,
        user_text: str,
        history: Optional[List[Tuple[str, str]]] = None,
        stream_callback: Optional[Callable[[str], None]] = None,
        use_cache: bool = True,
    ) -> str:
        """Synchronous chat. Returns full reply."""
        history = history or []

        # Check cache
        if use_cache and self.cache:
            cached = self.cache.get_response(user_text)
            if cached:
                log.debug("[LLMManager] Cache hit")
                return cached

        # Build optimized prompt
        payload = self.prompt_optimizer.prepare(
            history=history,
            new_user_message=user_text,
        )

        prompt = payload.get("prompt", user_text)

        # Enforce token budget
        ok, reason = self.token_optimizer.check_budget(prompt)
        if not ok:
            log.warning("[LLMManager] Token budget blocked: %s", reason)
            return f"[Rate limited] {reason}"

        # Execute via fallback chain
        result, provider = self.fallback_chain.execute(prompt, stream_callback=stream_callback)

        # Cache result
        if use_cache and self.cache and result:
            self.cache.set_response(user_text, result)

        # Record token usage
        self.token_optimizer.record_usage(
            prompt_tokens=max(1, len(prompt) // 4),
            completion_tokens=max(1, len(result) // 4),
            provider=provider,
        )

        return result

    def stream(
        self,
        user_text: str,
        history: Optional[List[Tuple[str, str]]] = None,
        ui_callback: Optional[Callable] = None,
    ) -> StreamingSession:
        """Async streaming — returns a StreamingSession immediately."""
        history = history or []
        session = self.streaming_manager.create_session(
            protocol=StreamProtocol.CALLBACK,
            ui_callback=ui_callback,
        )

        def _run() -> None:
            try:
                cb = self.streaming_manager.wrap_sync_callback(session)
                self.chat(user_text, history=history, stream_callback=cb)
            except Exception as exc:
                log.error("[LLMManager] Stream error: %s", exc)
            finally:
                session.finish()

        t = threading.Thread(target=_run, daemon=True)
        t.start()
        return session

    def _summarize_fn(self, text: str) -> str:
        """Internal summarization — uses the chain but no caching."""
        result, _ = self.fallback_chain.execute(text)
        return result

    # ------------------------------------------------------------------
    # System status
    # ------------------------------------------------------------------

    def status(self) -> Dict[str, Any]:
        return {
            "providers": self.provider_switcher.status(),
            "fallback_chain": self.fallback_chain.status(),
            "cache": self.cache.stats() if self.cache else {},
            "token_budget": self.token_optimizer.budget.stats(),
            "gpu": self.gpu.summary() if self.gpu else {"enabled": False},
            "quant": self.quant.summary(),
            "active_streams": len(self.streaming_manager.active_sessions()),
        }
