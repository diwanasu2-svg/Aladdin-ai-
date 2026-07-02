"""Token counting using tiktoken (falls back to word estimate)."""

from __future__ import annotations

import logging
from typing import Dict, List, Optional

log = logging.getLogger(__name__)

try:
    import tiktoken
    _TIKTOKEN_AVAILABLE = True
except ImportError:
    _TIKTOKEN_AVAILABLE = False
    log.warning("tiktoken not installed — using word-count estimate for tokens")


def _word_estimate(text: str) -> int:
    return max(1, int(len(text.split()) * 1.3))


class TokenCounter:
    """Count tokens for a given model/encoding."""

    _encodings: Dict[str, object] = {}

    @classmethod
    def _get_encoding(cls, model: str):
        if not _TIKTOKEN_AVAILABLE:
            return None
        if model not in cls._encodings:
            try:
                cls._encodings[model] = tiktoken.encoding_for_model(model)
            except Exception:
                try:
                    cls._encodings[model] = tiktoken.get_encoding("cl100k_base")
                except Exception:
                    cls._encodings[model] = None
        return cls._encodings[model]

    @classmethod
    def count(cls, text: str, model: str = "gpt-4") -> int:
        enc = cls._get_encoding(model)
        if enc is None:
            return _word_estimate(text)
        try:
            return len(enc.encode(text))
        except Exception:
            return _word_estimate(text)

    @classmethod
    def count_messages(cls, messages: List[Dict[str, str]], model: str = "gpt-4") -> int:
        total = 0
        for msg in messages:
            total += cls.count(msg.get("content", ""), model)
            total += 4  # per-message overhead
        total += 2  # reply priming
        return total
