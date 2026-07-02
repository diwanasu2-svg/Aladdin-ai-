"""Heuristic memory importance scoring for Smart Memory Part 3."""

from __future__ import annotations

import re
from typing import Any, Dict, Optional


class MemoryImportanceScorer:
    """Score how important a memory item is on a normalized 0..1 scale."""

    CATEGORY_WEIGHTS = {
        "identity": 0.95,
        "profile": 0.92,
        "contact": 0.90,
        "calendar": 0.88,
        "reminder": 0.88,
        "project": 0.82,
        "preference": 0.74,
        "preferences": 0.74,
        "location": 0.76,
        "summary": 0.62,
        "general": 0.55,
    }

    SOURCE_WEIGHTS = {
        "profile": 0.95,
        "fact": 0.75,
        "preference": 0.72,
        "contact": 0.90,
        "project": 0.84,
        "location": 0.76,
        "reminder": 0.88,
        "calendar": 0.88,
        "conversation_summary": 0.64,
        "general": 0.55,
    }

    def __init__(self, enabled: bool = True):
        self.enabled = bool(enabled)

    def score(
        self,
        text: str,
        *,
        category: str = "general",
        source_type: str = "general",
        metadata: Optional[Dict[str, Any]] = None,
        explicit_importance: Optional[int] = None,
    ) -> Dict[str, Any]:
        if not self.enabled:
            return {
                "score": 0.0,
                "level": 1,
                "reasons": ["importance scoring disabled"],
            }

        metadata = metadata or {}
        lowered = (text or "").lower()
        score = 0.10
        reasons = []

        score += 0.20 * self.CATEGORY_WEIGHTS.get(
            category, self.CATEGORY_WEIGHTS["general"]
        )
        score += 0.20 * self.SOURCE_WEIGHTS.get(
            source_type, self.SOURCE_WEIGHTS["general"]
        )

        if explicit_importance is not None:
            normalized = max(0.0, min(float(explicit_importance), 5.0)) / 5.0
            score += 0.28 * normalized
            reasons.append("explicit importance provided")

        if any(
            token in lowered for token in ("my name", "call me", "timezone", "language")
        ):
            score += 0.18
            reasons.append("identity signal")

        if re.search(r"\b\+?\d[\d\s().-]{6,}\b", text or ""):
            score += 0.16
            reasons.append("contains phone-like detail")

        if re.search(r"[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}", text or "", re.I):
            score += 0.16
            reasons.append("contains email detail")

        if any(key in metadata for key in ("due_at", "start_at", "end_at")):
            score += 0.14
            reasons.append("time-sensitive")

        if metadata.get("recurrence") not in (None, "", "none"):
            score += 0.10
            reasons.append("recurring memory")

        if metadata.get("is_favorite"):
            score += 0.12
            reasons.append("marked favorite")

        if metadata.get("priority"):
            score += min(float(metadata.get("priority", 0)) / 10.0, 0.12)
            reasons.append("priority metadata")

        if len((text or "").strip()) >= 40:
            score += 0.05
        if len((text or "").strip()) >= 120:
            score += 0.03

        score = max(0.0, min(score, 1.0))
        level = max(1, min(5, round(score * 4) + 1))
        return {
            "score": round(score, 4),
            "level": level,
            "reasons": reasons[:5] or ["baseline weighting"],
        }
