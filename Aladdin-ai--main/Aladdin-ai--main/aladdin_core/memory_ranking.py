"""Memory ranking for Smart Memory Part 3."""

from __future__ import annotations

import math
import re
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional


class MemoryRanker:
    """Combine similarity, importance and recency into a final rank score."""

    def __init__(
        self,
        similarity_weight: float = 0.55,
        importance_weight: float = 0.30,
        recency_weight: float = 0.15,
    ):
        total = similarity_weight + importance_weight + recency_weight
        if total <= 0.0:
            total = 1.0
        self.similarity_weight = similarity_weight / total
        self.importance_weight = importance_weight / total
        self.recency_weight = recency_weight / total

    def rank(
        self,
        items: List[Dict[str, Any]],
        *,
        query: str = "",
        top_k: Optional[int] = None,
    ) -> List[Dict[str, Any]]:
        ranked: List[Dict[str, Any]] = []
        for item in items:
            similarity = float(item.get("similarity", 0.0))
            importance = self._normalize_importance(item.get("importance", 0.0))
            recency = self._recency_score(
                item.get("updated_at") or item.get("created_at")
            )
            keyword_bonus = self._keyword_bonus(query, item.get("text", ""))
            score = (
                similarity * self.similarity_weight
                + importance * self.importance_weight
                + recency * self.recency_weight
                + keyword_bonus
            )
            enriched = dict(item)
            enriched["similarity_score"] = round(similarity, 4)
            enriched["importance_score"] = round(importance, 4)
            enriched["recency_score"] = round(recency, 4)
            enriched["rank_score"] = round(score, 4)
            ranked.append(enriched)
        ranked.sort(
            key=lambda x: (
                float(x.get("rank_score", 0.0)),
                float(x.get("similarity_score", 0.0)),
                float(x.get("importance_score", 0.0)),
            ),
            reverse=True,
        )
        if top_k is not None:
            return ranked[: max(int(top_k), 0)]
        return ranked

    @staticmethod
    def _normalize_importance(value: Any) -> float:
        try:
            val = float(value)
        except (TypeError, ValueError):
            return 0.0
        if val > 1.0:
            val = val / 5.0
        return max(0.0, min(val, 1.0))

    @staticmethod
    def _parse_datetime(value: Any) -> Optional[datetime]:
        if not value:
            return None
        if isinstance(value, datetime):
            return value if value.tzinfo else value.replace(tzinfo=timezone.utc)
        if isinstance(value, (int, float)):
            return datetime.fromtimestamp(float(value), tz=timezone.utc)
        if isinstance(value, str):
            raw = value.replace("Z", "+00:00")
            try:
                dt = datetime.fromisoformat(raw)
                return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)
            except ValueError:
                for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%d"):
                    try:
                        return datetime.strptime(value, fmt).replace(
                            tzinfo=timezone.utc
                        )
                    except ValueError:
                        continue
        return None

    def _recency_score(self, value: Any) -> float:
        dt = self._parse_datetime(value)
        if not dt:
            return 0.25
        now = datetime.now(timezone.utc)
        age_days = max((now - dt).total_seconds() / 86400.0, 0.0)
        return 1.0 / (1.0 + math.log1p(age_days))

    @staticmethod
    def _keyword_bonus(query: str, text: str) -> float:
        q_tokens = set(re.findall(r"\w+", (query or "").lower()))
        if not q_tokens:
            return 0.0
        t_tokens = set(re.findall(r"\w+", (text or "").lower()))
        if not t_tokens:
            return 0.0
        overlap = len(q_tokens & t_tokens) / max(len(q_tokens), 1)
        exact_phrase = (
            0.04 if (query or "").strip().lower() in (text or "").lower() else 0.0
        )
        return min(overlap * 0.10 + exact_phrase, 0.18)
