"""models/token_optimizer.py — Phase 14, Feature 10: Token Optimization.

Removes unnecessary tokens, summarizes long conversations, monitors token
usage per request, and implements automatic context pruning with budget
management.
"""

from __future__ import annotations

import logging
import re
import time
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional, Tuple

log = logging.getLogger(__name__)


@dataclass
class TokenUsage:
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0
    cost_usd: float = 0.0
    provider: str = ""
    timestamp: float = field(default_factory=time.time)

    @property
    def efficiency_ratio(self) -> float:
        """Ratio of useful output to input — higher is better."""
        if self.prompt_tokens == 0:
            return 0.0
        return self.completion_tokens / self.prompt_tokens


class TokenBudget:
    """Tracks and enforces token usage budgets."""

    def __init__(
        self,
        max_tokens_per_request: int = 4096,
        max_tokens_per_day: int = 100_000,
        cost_per_1k_tokens: float = 0.002,  # USD
    ) -> None:
        self.max_per_request = max_tokens_per_request
        self.max_per_day = max_tokens_per_day
        self.cost_per_1k = cost_per_1k_tokens
        self._daily_usage: int = 0
        self._daily_cost: float = 0.0
        self._request_history: List[TokenUsage] = []
        self._day_start = time.time()

    def _reset_if_new_day(self) -> None:
        if time.time() - self._day_start >= 86400:
            self._daily_usage = 0
            self._daily_cost = 0.0
            self._day_start = time.time()
            log.info("[TokenBudget] Daily counter reset")

    def record(self, usage: TokenUsage) -> None:
        self._reset_if_new_day()
        self._daily_usage += usage.total_tokens
        self._daily_cost += usage.cost_usd
        self._request_history.append(usage)
        if len(self._request_history) > 10000:
            self._request_history = self._request_history[-5000:]

    def can_proceed(self, estimated_tokens: int) -> Tuple[bool, str]:
        self._reset_if_new_day()
        if estimated_tokens > self.max_per_request:
            return False, f"Request exceeds per-request limit ({estimated_tokens} > {self.max_per_request})"
        if self._daily_usage + estimated_tokens > self.max_per_day:
            remaining = self.max_per_day - self._daily_usage
            return False, f"Daily budget exhausted. Remaining: {remaining} tokens"
        return True, "ok"

    def stats(self) -> Dict[str, Any]:
        self._reset_if_new_day()
        return {
            "daily_tokens_used": self._daily_usage,
            "daily_tokens_limit": self.max_per_day,
            "daily_cost_usd": round(self._daily_cost, 4),
            "utilization_pct": round(self._daily_usage / self.max_per_day * 100, 1),
            "total_requests": len(self._request_history),
        }


class ConversationSummarizer:
    """Uses the LLM itself to summarize long conversations."""

    SUMMARIZE_PROMPT = (
        "Summarize the following conversation in 3-5 concise bullet points, "
        "capturing all important facts, decisions, and context. "
        "Be brief and factual.\n\n"
        "Conversation:\n{conversation}\n\n"
        "Summary:"
    )

    def __init__(self, llm_fn: Optional[Callable[[str], str]] = None) -> None:
        self._llm_fn = llm_fn

    def summarize(self, messages: List[Dict[str, str]]) -> str:
        """Summarize a list of {role, content} dicts into a short text."""
        if not messages:
            return ""

        conversation_text = "\n".join(
            f"{m['role'].upper()}: {m['content']}" for m in messages
        )

        if self._llm_fn:
            try:
                prompt = self.SUMMARIZE_PROMPT.format(conversation=conversation_text[:6000])
                return self._llm_fn(prompt).strip()
            except Exception as exc:
                log.warning("[TokenOpt] Summarizer LLM call failed: %s", exc)

        # Extractive fallback — take first sentence from each assistant message
        lines = []
        for m in messages:
            if m.get("role") == "assistant" and m.get("content"):
                first_sent = re.split(r"[.!?]", m["content"])[0].strip()
                if first_sent:
                    lines.append(f"• {first_sent}")
        return "\n".join(lines[:5]) or "Previous conversation summarized."


class TokenOptimizer:
    """End-to-end token optimization for all LLM calls."""

    # Patterns for noise removal
    _NOISE_PATTERNS = [
        re.compile(r"\n{3,}", re.MULTILINE),          # 3+ blank lines → 1
        re.compile(r"[ \t]{2,}"),                      # multiple spaces → 1
        re.compile(r"^[\s•\-*]+$", re.MULTILINE),     # empty bullet lines
    ]

    def __init__(
        self,
        max_context_tokens: int = 4096,
        summarize_threshold: int = 3000,  # start summarizing when history > N tokens
        llm_fn: Optional[Callable[[str], str]] = None,
        budget: Optional[TokenBudget] = None,
    ) -> None:
        self._max_context = max_context_tokens
        self._summarize_threshold = summarize_threshold
        self._summarizer = ConversationSummarizer(llm_fn=llm_fn)
        self.budget = budget or TokenBudget(max_tokens_per_request=max_context_tokens)
        self._conversation_summary: Optional[str] = None

    # ------------------------------------------------------------------
    # Token counting
    # ------------------------------------------------------------------

    @staticmethod
    def estimate_tokens(text: str) -> int:
        """Fast heuristic: ~4 chars per token (GPT-style)."""
        return max(1, len(text) // 4)

    def count_messages_tokens(self, messages: List[Dict[str, str]]) -> int:
        return sum(self.estimate_tokens(m.get("content", "")) for m in messages)

    # ------------------------------------------------------------------
    # Text cleaning
    # ------------------------------------------------------------------

    def clean_text(self, text: str) -> str:
        """Remove unnecessary whitespace and noise."""
        for pattern in self._NOISE_PATTERNS:
            if pattern.groups == 0:
                text = pattern.sub(" " if " " in pattern.pattern else "\n", text)
            else:
                text = pattern.sub(" ", text)
        return text.strip()

    def remove_repetitions(self, messages: List[Dict[str, str]]) -> List[Dict[str, str]]:
        """Remove exact duplicate consecutive messages."""
        if not messages:
            return messages
        result = [messages[0]]
        for msg in messages[1:]:
            if msg.get("content", "").strip() != result[-1].get("content", "").strip():
                result.append(msg)
        return result

    # ------------------------------------------------------------------
    # Context pruning
    # ------------------------------------------------------------------

    def prune_messages(
        self,
        messages: List[Dict[str, str]],
        system_tokens: int = 0,
        reserve_for_response: int = 512,
    ) -> List[Dict[str, str]]:
        """Trim oldest messages to stay within context window."""
        budget = self._max_context - system_tokens - reserve_for_response

        # Check if we need to summarize
        current_tokens = self.count_messages_tokens(messages)
        if current_tokens > self._summarize_threshold and len(messages) > 4:
            # Summarize the first half of the conversation
            half = len(messages) // 2
            old_msgs = messages[:half]
            self._conversation_summary = self._summarizer.summarize(old_msgs)
            messages = messages[half:]
            log.info("[TokenOpt] Summarized %d old messages → %d chars",
                     half, len(self._conversation_summary))

        # Hard trim if still too long
        while messages and self.count_messages_tokens(messages) > budget:
            messages = messages[2:] if len(messages) >= 2 else []

        return messages

    # ------------------------------------------------------------------
    # Budget enforcement
    # ------------------------------------------------------------------

    def check_budget(self, prompt: str, context: str = "") -> Tuple[bool, str]:
        estimated = self.estimate_tokens(prompt) + self.estimate_tokens(context)
        return self.budget.can_proceed(estimated)

    def record_usage(
        self,
        prompt_tokens: int,
        completion_tokens: int,
        provider: str = "",
        cost_per_1k: float = 0.002,
    ) -> TokenUsage:
        usage = TokenUsage(
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
            total_tokens=prompt_tokens + completion_tokens,
            cost_usd=(prompt_tokens + completion_tokens) / 1000 * cost_per_1k,
            provider=provider,
        )
        self.budget.record(usage)
        log.debug("[TokenOpt] Usage: prompt=%d completion=%d total=%d cost=$%.4f",
                  prompt_tokens, completion_tokens, usage.total_tokens, usage.cost_usd)
        return usage

    # ------------------------------------------------------------------
    # Full pipeline
    # ------------------------------------------------------------------

    def optimize_messages(
        self,
        messages: List[Dict[str, str]],
        system_prompt: str = "",
        reserve_for_response: int = 512,
    ) -> Tuple[List[Dict[str, str]], Optional[str]]:
        """Clean, deduplicate, and prune messages. Returns (messages, summary_if_any)."""
        # Clean content
        cleaned = [
            {**m, "content": self.clean_text(m.get("content", ""))}
            for m in messages
        ]
        # Remove duplicates
        cleaned = self.remove_repetitions(cleaned)
        # Prune to fit context
        sys_tokens = self.estimate_tokens(system_prompt)
        cleaned = self.prune_messages(cleaned, system_tokens=sys_tokens,
                                      reserve_for_response=reserve_for_response)

        return cleaned, self._conversation_summary

    @property
    def conversation_summary(self) -> Optional[str]:
        return self._conversation_summary
