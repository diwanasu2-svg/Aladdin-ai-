"""models/fallback_chain.py — Phase 14, Feature 5: Fallback Chain.

Multi-level fallback: primary → secondary → tertiary → rule-based.
No service interruption from the user's perspective.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Dict, List, Optional, Tuple

log = logging.getLogger(__name__)


class FallbackLevel(int, Enum):
    PRIMARY   = 0
    SECONDARY = 1
    TERTIARY  = 2
    RULE_BASED = 3


@dataclass
class FallbackEntry:
    level: FallbackLevel
    name: str
    handler: Callable[..., str]
    availability_check: Optional[Callable[[], bool]] = None
    retry_attempts: int = 2
    retry_delay: float = 0.5
    timeout: float = 30.0
    last_failure: float = 0.0
    failure_count: int = 0
    cooldown: float = 60.0

    @property
    def is_on_cooldown(self) -> bool:
        return time.time() - self.last_failure < self.cooldown

    def is_available(self) -> bool:
        if self.is_on_cooldown:
            return False
        if self.availability_check:
            try:
                return self.availability_check()
            except Exception:
                return False
        return True

    def record_failure(self) -> None:
        self.failure_count += 1
        self.last_failure = time.time()


class FallbackChain:
    """Executes inference through an ordered chain of providers with graceful degradation."""

    def __init__(self) -> None:
        self._chain: List[FallbackEntry] = []
        self._last_successful_level: Optional[FallbackLevel] = None
        self._total_calls: int = 0
        self._degradation_events: int = 0

    # ------------------------------------------------------------------
    # Chain construction
    # ------------------------------------------------------------------

    def add(
        self,
        name: str,
        handler: Callable[..., str],
        level: FallbackLevel = FallbackLevel.PRIMARY,
        availability_check: Optional[Callable[[], bool]] = None,
        retry_attempts: int = 2,
        timeout: float = 30.0,
        cooldown: float = 60.0,
    ) -> "FallbackChain":
        """Fluent API: chain.add(...).add(...).add(...)"""
        entry = FallbackEntry(
            level=level,
            name=name,
            handler=handler,
            availability_check=availability_check,
            retry_attempts=retry_attempts,
            timeout=timeout,
            cooldown=cooldown,
        )
        self._chain.append(entry)
        self._chain.sort(key=lambda e: e.level.value)
        log.info("[FallbackChain] Added %s at level %s", name, level.name)
        return self

    # ------------------------------------------------------------------
    # Execution
    # ------------------------------------------------------------------

    def execute(
        self,
        *args: Any,
        stream_callback: Optional[Callable[[str], None]] = None,
        **kwargs: Any,
    ) -> Tuple[str, str]:
        """Run the chain; returns (result, provider_name)."""
        self._total_calls += 1

        for entry in self._chain:
            if not entry.is_available():
                log.debug("[FallbackChain] Skipping %s (unavailable/cooldown)", entry.name)
                continue

            result, ok = self._try_entry(entry, args, kwargs, stream_callback)
            if ok:
                if entry.level.value > 0:
                    self._degradation_events += 1
                    log.warning("[FallbackChain] Degraded to level %s: %s",
                                entry.level.name, entry.name)
                self._last_successful_level = entry.level
                return result, entry.name

        # Ultimate fallback — should never be reached if rule_based is in chain
        log.error("[FallbackChain] All entries exhausted — returning error message")
        return "I'm sorry, all AI services are currently unavailable. Please try again later.", "none"

    def _try_entry(
        self,
        entry: FallbackEntry,
        args: tuple,
        kwargs: dict,
        stream_callback: Optional[Callable[[str], None]],
    ) -> Tuple[str, bool]:
        import signal
        import functools

        for attempt in range(1, entry.retry_attempts + 1):
            try:
                call_kwargs = dict(kwargs)
                if stream_callback is not None:
                    call_kwargs["stream_callback"] = stream_callback

                result = entry.handler(*args, **call_kwargs)
                log.info("[FallbackChain] %s succeeded (attempt %d)", entry.name, attempt)
                entry.failure_count = 0  # reset on success
                return result, True

            except Exception as exc:
                log.warning("[FallbackChain] %s attempt %d/%d failed: %s",
                            entry.name, attempt, entry.retry_attempts, exc)
                if attempt < entry.retry_attempts:
                    time.sleep(entry.retry_delay * attempt)
                else:
                    entry.record_failure()

        return "", False

    # ------------------------------------------------------------------
    # Diagnostics
    # ------------------------------------------------------------------

    def status(self) -> Dict[str, Any]:
        return {
            "total_calls": self._total_calls,
            "degradation_events": self._degradation_events,
            "last_level": self._last_successful_level.name if self._last_successful_level else None,
            "entries": [
                {
                    "name": e.name,
                    "level": e.level.name,
                    "failures": e.failure_count,
                    "available": e.is_available(),
                    "on_cooldown": e.is_on_cooldown,
                }
                for e in self._chain
            ],
        }

    def reset_cooldowns(self) -> None:
        """Force-reset all cooldowns (useful for tests or manual recovery)."""
        for entry in self._chain:
            entry.last_failure = 0.0
            entry.failure_count = 0


# ---------------------------------------------------------------------------
# Convenience factory
# ---------------------------------------------------------------------------

def build_default_chain(
    gemini_handler: Optional[Callable] = None,
    openai_handler: Optional[Callable] = None,
    ollama_handler: Optional[Callable] = None,
    local_llm_handler: Optional[Callable] = None,
    rule_based_handler: Optional[Callable] = None,
) -> FallbackChain:
    """Build a ready-to-use fallback chain from available handlers."""
    chain = FallbackChain()

    if gemini_handler:
        chain.add("gemini", gemini_handler, FallbackLevel.PRIMARY, timeout=20.0)
    if openai_handler:
        chain.add("openai", openai_handler, FallbackLevel.SECONDARY, timeout=25.0)
    if ollama_handler:
        chain.add("ollama", ollama_handler, FallbackLevel.TERTIARY, timeout=60.0)
    if local_llm_handler:
        chain.add("local_llm", local_llm_handler, FallbackLevel.TERTIARY, timeout=120.0)
    if rule_based_handler:
        chain.add("rule_based", rule_based_handler, FallbackLevel.RULE_BASED,
                  retry_attempts=1, cooldown=0.0)
    return chain
