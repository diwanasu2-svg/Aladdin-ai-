"""models/provider_switcher.py — Phase 14, Feature 3: Provider Auto-Switch.

Registers all providers (Gemini, OpenAI, Anthropic, Ollama, LocalLLM) and
automatically switches if one fails, using local model when offline.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Callable, Dict, List, Optional, Any

log = logging.getLogger(__name__)


class ProviderName(str, Enum):
    GEMINI    = "gemini"
    OPENAI    = "openai"
    ANTHROPIC = "anthropic"
    OLLAMA    = "ollama"
    LOCAL_LLM = "local_llm"
    MLC_LLM   = "mlc_llm"
    RULE_BASED = "rule_based"


@dataclass
class ProviderStats:
    name: ProviderName
    priority: int            # lower = higher priority
    avg_latency_ms: float = 0.0
    success_count: int = 0
    failure_count: int = 0
    last_failure: float = 0.0
    cooldown_seconds: float = 30.0
    cost_per_1k_tokens: float = 0.0  # USD

    @property
    def failure_rate(self) -> float:
        total = self.success_count + self.failure_count
        return self.failure_count / total if total else 0.0

    @property
    def in_cooldown(self) -> bool:
        return time.time() - self.last_failure < self.cooldown_seconds

    @property
    def score(self) -> float:
        """Combined score — lower is better (cost + latency + penalty)."""
        penalty = 1e6 if self.in_cooldown else 0.0
        latency_score = self.avg_latency_ms / 1000.0
        failure_penalty = self.failure_rate * 500
        return self.priority * 10 + latency_score + failure_penalty + penalty


class ProviderSwitcher:
    """Central registry for all AI providers with automatic failover."""

    _DEFAULT_PRIORITY: Dict[ProviderName, int] = {
        ProviderName.GEMINI:    1,
        ProviderName.OPENAI:    2,
        ProviderName.ANTHROPIC: 3,
        ProviderName.OLLAMA:    4,
        ProviderName.MLC_LLM:  5,
        ProviderName.LOCAL_LLM: 6,
        ProviderName.RULE_BASED: 99,
    }

    _DEFAULT_COSTS: Dict[ProviderName, float] = {
        ProviderName.GEMINI:    0.0005,
        ProviderName.OPENAI:    0.002,
        ProviderName.ANTHROPIC: 0.003,
        ProviderName.OLLAMA:    0.0,
        ProviderName.MLC_LLM:  0.0,
        ProviderName.LOCAL_LLM: 0.0,
        ProviderName.RULE_BASED: 0.0,
    }

    def __init__(self) -> None:
        self._providers: Dict[ProviderName, ProviderStats] = {}
        self._handlers: Dict[ProviderName, Callable] = {}
        self._availability_checkers: Dict[ProviderName, Callable[[], bool]] = {}
        self._active_provider: Optional[ProviderName] = None
        self._register_defaults()

    # ------------------------------------------------------------------
    # Registration
    # ------------------------------------------------------------------

    def _register_defaults(self) -> None:
        for name in ProviderName:
            self._providers[name] = ProviderStats(
                name=name,
                priority=self._DEFAULT_PRIORITY.get(name, 50),
                cost_per_1k_tokens=self._DEFAULT_COSTS.get(name, 0.0),
            )

    def register_handler(
        self,
        provider: ProviderName,
        handler: Callable[[str, Optional[Callable[[str], None]]], str],
        availability_check: Optional[Callable[[], bool]] = None,
    ) -> None:
        """Bind a provider name to an actual inference callable."""
        self._handlers[provider] = handler
        if availability_check:
            self._availability_checkers[provider] = availability_check
        log.info("[ProviderSwitcher] Registered: %s", provider.value)

    def set_priority(self, provider: ProviderName, priority: int) -> None:
        if provider in self._providers:
            self._providers[provider].priority = priority

    # ------------------------------------------------------------------
    # Availability
    # ------------------------------------------------------------------

    def _is_online(self) -> bool:
        """Simple network connectivity check."""
        import socket
        try:
            socket.setdefaulttimeout(3)
            socket.socket(socket.AF_INET, socket.SOCK_STREAM).connect(("8.8.8.8", 53))
            return True
        except Exception:
            return False

    def _is_provider_available(self, name: ProviderName) -> bool:
        checker = self._availability_checkers.get(name)
        if checker:
            try:
                return checker()
            except Exception:
                return False
        # Default: cloud providers need internet
        if name in (ProviderName.GEMINI, ProviderName.OPENAI, ProviderName.ANTHROPIC):
            return self._is_online()
        return name in self._handlers

    # ------------------------------------------------------------------
    # Ranked list
    # ------------------------------------------------------------------

    def _ranked_providers(self) -> List[ProviderName]:
        """Return providers ordered by score (best first)."""
        registered = [p for p in self._providers if p in self._handlers]
        return sorted(registered, key=lambda p: self._providers[p].score)

    # ------------------------------------------------------------------
    # Inference with auto-switch
    # ------------------------------------------------------------------

    def generate(
        self,
        prompt: str,
        stream_callback: Optional[Callable[[str], None]] = None,
    ) -> str:
        """Try providers in ranked order until one succeeds."""
        if not self._is_online():
            log.info("[ProviderSwitcher] Offline — forcing local providers")
            return self._try_local_only(prompt, stream_callback)

        for provider in self._ranked_providers():
            if not self._is_provider_available(provider):
                log.debug("[ProviderSwitcher] Skipping unavailable: %s", provider.value)
                continue
            if self._providers[provider].in_cooldown:
                log.debug("[ProviderSwitcher] Skipping cooldown: %s", provider.value)
                continue

            try:
                result = self._call_provider(provider, prompt, stream_callback)
                self._record_success(provider)
                self._active_provider = provider
                return result
            except Exception as exc:
                log.warning("[ProviderSwitcher] %s failed: %s — switching", provider.value, exc)
                self._record_failure(provider)

        log.error("[ProviderSwitcher] All providers failed — using rule-based fallback")
        return self._rule_based_fallback(prompt)

    def _try_local_only(
        self,
        prompt: str,
        stream_callback: Optional[Callable[[str], None]],
    ) -> str:
        for provider in [ProviderName.LOCAL_LLM, ProviderName.MLC_LLM, ProviderName.OLLAMA]:
            if provider in self._handlers:
                try:
                    result = self._call_provider(provider, prompt, stream_callback)
                    self._record_success(provider)
                    self._active_provider = provider
                    return result
                except Exception as exc:
                    log.warning("[ProviderSwitcher] Local %s failed: %s", provider.value, exc)
                    self._record_failure(provider)
        return self._rule_based_fallback(prompt)

    def _call_provider(
        self,
        provider: ProviderName,
        prompt: str,
        stream_callback: Optional[Callable[[str], None]],
    ) -> str:
        handler = self._handlers[provider]
        t0 = time.monotonic()
        result = handler(prompt, stream_callback)
        latency_ms = (time.monotonic() - t0) * 1000
        stats = self._providers[provider]
        # Exponential moving average
        alpha = 0.2
        stats.avg_latency_ms = (1 - alpha) * stats.avg_latency_ms + alpha * latency_ms
        return result

    def _record_success(self, provider: ProviderName) -> None:
        self._providers[provider].success_count += 1

    def _record_failure(self, provider: ProviderName) -> None:
        stats = self._providers[provider]
        stats.failure_count += 1
        stats.last_failure = time.time()

    @staticmethod
    def _rule_based_fallback(prompt: str) -> str:
        prompt_lower = prompt.lower()
        if any(w in prompt_lower for w in ("hello", "hi", "hey")):
            return "Hello! I'm currently offline but happy to help with basic queries."
        if any(w in prompt_lower for w in ("time", "date")):
            return f"The current date/time is: {time.strftime('%Y-%m-%d %H:%M:%S')}"
        return "I'm sorry, I'm unable to process your request right now. All AI providers are unavailable."

    # ------------------------------------------------------------------
    # Diagnostics
    # ------------------------------------------------------------------

    def status(self) -> Dict[str, Any]:
        return {
            p.value: {
                "priority": s.priority,
                "success": s.success_count,
                "failure": s.failure_count,
                "avg_latency_ms": round(s.avg_latency_ms, 1),
                "in_cooldown": s.in_cooldown,
                "score": round(s.score, 2),
            }
            for p, s in self._providers.items()
        }

    @property
    def active_provider(self) -> Optional[ProviderName]:
        return self._active_provider
