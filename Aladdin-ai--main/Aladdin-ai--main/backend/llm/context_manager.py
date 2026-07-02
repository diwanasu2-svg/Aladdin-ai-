"""
Context window manager — Task 18 (fix trimmer break→continue) + Task 31 (GPT-3.5 context 16385).
"""
from __future__ import annotations

import logging
from typing import Dict, List, Optional

from .token_counter import TokenCounter

log = logging.getLogger(__name__)

# Task 31: Updated GPT-3.5-turbo context limit from 4096 → 16385
MODEL_LIMITS = {
    "gpt-4": 8192,
    "gpt-4o": 128000,
    "gpt-4o-mini": 128000,
    "gpt-3.5-turbo": 16385,         # Task 31: was 4096, now 16385
    "gpt-3.5-turbo-16k": 16385,
    "claude-3-opus-20240229": 200000,
    "claude-3-sonnet-20240229": 200000,
    "claude-3-haiku-20240307": 200000,
    "gemini-1.5-pro": 1000000,
    "gemini-1.5-flash": 1000000,
    "llama3.1": 8192,
    "default": 16385,               # Task 31: default also updated
}


class ContextManager:
    """Sliding-window context trimmer — Task 18: fixed oversized-message handling."""

    def __init__(self, max_tokens: int = 16385, reserve_for_response: int = 512) -> None:
        self._max_tokens = max_tokens
        self._reserve = reserve_for_response

    def get_limit(self, model: str) -> int:
        for key in MODEL_LIMITS:
            if key in model.lower():
                return MODEL_LIMITS[key]
        return MODEL_LIMITS["default"]

    def trim(
        self,
        messages: List[Dict[str, str]],
        model: str = "default",
        system_prompt: Optional[str] = None,
    ) -> List[Dict[str, str]]:
        """
        Return trimmed message list that fits within the model's context window.
        Task 18: Fixed — oversized individual messages are skipped with `continue`,
                 not aborting the entire loop with `break`.
        """
        limit = min(self._max_tokens, self.get_limit(model)) - self._reserve

        result: List[Dict[str, str]] = []
        if system_prompt:
            result.append({"role": "system", "content": system_prompt})

        non_system = [m for m in messages if m.get("role") != "system"]

        kept = []
        used = TokenCounter.count_messages(result, model)
        for msg in reversed(non_system):
            msg_tokens = TokenCounter.count(msg.get("content", ""), model) + 4
            if msg_tokens > limit:
                # Task 18: skip oversized single messages instead of aborting loop
                log.warning(
                    "Context trim: skipping oversized message (%d tokens, limit %d). "
                    "Consider chunking large content.",
                    msg_tokens, limit,
                )
                continue  # Task 18: was `break` — now we keep processing older messages
            if used + msg_tokens <= limit:
                kept.insert(0, msg)
                used += msg_tokens
            else:
                log.debug("Context trim: dropped older message (token budget exhausted)")
                break

        total_dropped = len(non_system) - len(kept)
        if total_dropped > 0:
            log.info(
                "Context trim: kept %d/%d messages for model=%s (dropped %d)",
                len(kept), len(non_system), model, total_dropped,
            )

        return result + kept
