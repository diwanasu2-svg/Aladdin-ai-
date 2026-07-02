"""models/prompt_optimizer.py — Phase 14, Feature 9: Prompt Optimization.

Optimizes system prompts for different models, trims context to fit the
context window, removes redundancy, and builds dynamic prompt templates.
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

log = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Model-specific prompt formats
# ---------------------------------------------------------------------------

PROMPT_TEMPLATES: Dict[str, Dict[str, str]] = {
    "llama2": {
        "system_prefix": "[INST] <<SYS>>\n",
        "system_suffix": "\n<</SYS>>\n\n",
        "user_prefix": "",
        "user_suffix": " [/INST]",
        "assistant_prefix": "",
        "assistant_suffix": " </s><s>",
    },
    "mistral": {
        "system_prefix": "[INST] ",
        "system_suffix": "\n\n",
        "user_prefix": "",
        "user_suffix": " [/INST]",
        "assistant_prefix": "",
        "assistant_suffix": "</s>",
    },
    "phi3": {
        "system_prefix": "<|system|>\n",
        "system_suffix": "<|end|>\n",
        "user_prefix": "<|user|>\n",
        "user_suffix": "<|end|>\n",
        "assistant_prefix": "<|assistant|>\n",
        "assistant_suffix": "<|end|>\n",
    },
    "gemma": {
        "system_prefix": "<start_of_turn>user\n",
        "system_suffix": "\n",
        "user_prefix": "",
        "user_suffix": "<end_of_turn>\n<start_of_turn>model\n",
        "assistant_prefix": "",
        "assistant_suffix": "<end_of_turn>\n",
    },
    "ollama": {
        "system_prefix": "",
        "system_suffix": "\n",
        "user_prefix": "User: ",
        "user_suffix": "\n",
        "assistant_prefix": "Assistant: ",
        "assistant_suffix": "\n",
    },
    "openai": {  # OpenAI / Anthropic use messages API — no manual templating needed
        "system_prefix": "",
        "system_suffix": "",
        "user_prefix": "",
        "user_suffix": "",
        "assistant_prefix": "",
        "assistant_suffix": "",
    },
}


@dataclass
class Message:
    role: str   # "system" | "user" | "assistant"
    content: str

    def token_estimate(self) -> int:
        """Fast token count estimate: ~4 chars per token."""
        return max(1, len(self.content) // 4)


class PromptOptimizer:
    """Builds and optimizes prompts for any model family."""

    # Deduplication: patterns that are nearly identical in consecutive messages
    _FILLER_PATTERNS = [
        re.compile(r"\b(um|uh|hmm|ah|okay|ok|right|sure|alright)\b", re.IGNORECASE),
        re.compile(r"\s{2,}"),
    ]

    def __init__(
        self,
        model_family: str = "ollama",
        max_context_tokens: int = 4096,
        system_prompt: str = "You are Aladdin, a helpful AI assistant.",
        reserve_tokens: int = 512,  # tokens reserved for the model's response
    ) -> None:
        self._family = model_family
        self._max_tokens = max_context_tokens
        self._base_system = system_prompt
        self._reserve = reserve_tokens
        self._template = PROMPT_TEMPLATES.get(model_family, PROMPT_TEMPLATES["ollama"])

    # ------------------------------------------------------------------
    # System prompt construction
    # ------------------------------------------------------------------

    def build_system_prompt(
        self,
        extra_context: Optional[str] = None,
        memory_summary: Optional[str] = None,
        tool_descriptions: Optional[str] = None,
    ) -> str:
        """Compose the system prompt from base + dynamic sections."""
        parts = [self._base_system.strip()]

        if memory_summary:
            parts.append(f"\n\n## Relevant Memory\n{memory_summary.strip()}")
        if tool_descriptions:
            parts.append(f"\n\n## Available Tools\n{tool_descriptions.strip()}")
        if extra_context:
            parts.append(f"\n\n## Context\n{extra_context.strip()}")

        return "\n".join(parts)

    # ------------------------------------------------------------------
    # Context trimming
    # ------------------------------------------------------------------

    def trim_history(
        self,
        history: List[Message],
        new_user_message: str,
        system_prompt: str = "",
    ) -> List[Message]:
        """Remove oldest messages until everything fits in the context window."""
        sys_tokens = max(1, len(system_prompt) // 4)
        user_tokens = max(1, len(new_user_message) // 4)
        budget = self._max_tokens - self._reserve - sys_tokens - user_tokens

        # Always keep newest messages; drop oldest pairs first
        trimmed = list(history)
        while trimmed:
            total = sum(m.token_estimate() for m in trimmed)
            if total <= budget:
                break
            # Drop the oldest user+assistant pair
            if len(trimmed) >= 2:
                trimmed = trimmed[2:]
            else:
                trimmed = []

        dropped = len(history) - len(trimmed)
        if dropped > 0:
            log.debug("[Prompt] Trimmed %d messages to fit context", dropped)

        return trimmed

    # ------------------------------------------------------------------
    # Deduplication
    # ------------------------------------------------------------------

    def deduplicate(self, messages: List[Message]) -> List[Message]:
        """Remove duplicate consecutive user messages."""
        if not messages:
            return messages
        deduped: List[Message] = [messages[0]]
        for msg in messages[1:]:
            if msg.content.strip() != deduped[-1].content.strip():
                deduped.append(msg)
        return deduped

    def clean_fillers(self, text: str) -> str:
        """Strip filler words and normalize whitespace."""
        for pattern in self._FILLER_PATTERNS:
            text = pattern.sub(" ", text)
        return text.strip()

    # ------------------------------------------------------------------
    # Prompt formatting
    # ------------------------------------------------------------------

    def format_prompt(
        self,
        messages: List[Message],
        system_prompt: str = "",
    ) -> str:
        """Format a full prompt string for GGUF-style models."""
        t = self._template
        result_parts = []

        if system_prompt:
            result_parts.append(t["system_prefix"] + system_prompt.strip() + t["system_suffix"])

        for msg in messages:
            if msg.role == "user":
                result_parts.append(t["user_prefix"] + msg.content.strip() + t["user_suffix"])
            elif msg.role == "assistant":
                result_parts.append(
                    t["assistant_prefix"] + msg.content.strip() + t["assistant_suffix"]
                )

        # Final assistant prefix to trigger generation
        result_parts.append(t["assistant_prefix"])
        return "".join(result_parts)

    def to_messages_api(
        self,
        messages: List[Message],
        system_prompt: str = "",
    ) -> List[Dict[str, str]]:
        """Convert to OpenAI-style messages list for API providers."""
        result = []
        if system_prompt:
            result.append({"role": "system", "content": system_prompt})
        for m in messages:
            result.append({"role": m.role, "content": m.content})
        return result

    # ------------------------------------------------------------------
    # Full pipeline
    # ------------------------------------------------------------------

    def prepare(
        self,
        history: List[Tuple[str, str]],  # list of (user, assistant) pairs
        new_user_message: str,
        extra_context: Optional[str] = None,
        memory_summary: Optional[str] = None,
        tool_descriptions: Optional[str] = None,
    ) -> Dict[str, Any]:
        """End-to-end prompt preparation. Returns format-specific payload."""
        system = self.build_system_prompt(extra_context, memory_summary, tool_descriptions)

        messages = []
        for u, a in history:
            messages.append(Message(role="user", content=u))
            if a:
                messages.append(Message(role="assistant", content=a))

        messages = self.deduplicate(messages)
        messages = self.trim_history(messages, new_user_message, system)

        # Add new user message
        messages.append(Message(role="user", content=self.clean_fillers(new_user_message)))

        if self._family in ("openai", "anthropic", "gemini"):
            return {
                "type": "messages",
                "system": system,
                "messages": self.to_messages_api(messages, system),
            }
        else:
            return {
                "type": "prompt",
                "system": system,
                "prompt": self.format_prompt(messages, system),
            }
