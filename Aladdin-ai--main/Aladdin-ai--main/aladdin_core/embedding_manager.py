"""Embedding manager for Smart Memory Part 3.

Dependency-free semantic embeddings based on stable hashed features.
This avoids adding heavy ML dependencies while still providing useful
semantic recall across memory records.
"""

from __future__ import annotations

import hashlib
import math
import re
from functools import lru_cache
from typing import Iterable, List, Sequence, Tuple

_TOKEN_RE = re.compile(r"[a-z0-9_@.+-]+", re.IGNORECASE)


class EmbeddingManager:
    """Create deterministic vector embeddings from text.

    Strategy:
      * word tokens + short character n-grams
      * stable hashing into a fixed-size vector
      * L2 normalization for cosine similarity

    This is intentionally lightweight and dependency-free for maximum
    backward compatibility with the existing local/offline architecture.
    """

    def __init__(self, dimensions: int = 256, cache_size: int = 1024):
        self.dimensions = max(int(dimensions or 256), 32)
        self.cache_size = max(int(cache_size or 0), 0)
        if self.cache_size > 0:
            self._embed_cached = lru_cache(maxsize=self.cache_size)(
                self._embed_text_uncached
            )
        else:  # pragma: no cover - configuration branch
            self._embed_cached = self._embed_text_uncached

    @staticmethod
    def normalize_text(text: str) -> str:
        return re.sub(r"\s+", " ", (text or "").strip().lower())

    @classmethod
    def tokenize(cls, text: str) -> List[str]:
        normalized = cls.normalize_text(text)
        if not normalized:
            return []
        tokens = _TOKEN_RE.findall(normalized)
        if not tokens:
            return []

        features = list(tokens)
        if len(tokens) > 1:
            features.extend(
                f"{tokens[i]}__{tokens[i + 1]}" for i in range(len(tokens) - 1)
            )
        if len(normalized) <= 120:
            compact = normalized.replace(" ", "_")
            for n in (3, 4):
                for i in range(max(len(compact) - n + 1, 0)):
                    features.append(f"char{n}:{compact[i:i+n]}")
        return features

    def _hash_feature(self, feature: str) -> Tuple[int, float]:
        digest = hashlib.blake2b(feature.encode("utf-8"), digest_size=8).digest()
        index = int.from_bytes(digest[:4], "big") % self.dimensions
        sign = 1.0 if digest[4] % 2 == 0 else -1.0
        return index, sign

    def _feature_weight(self, feature: str) -> float:
        base = 1.0
        if feature.startswith("char"):
            base = 0.35
        elif "__" in feature:
            base = 1.15
        length_bonus = min(len(feature), 12) / 24.0
        return base + length_bonus

    def _embed_text_uncached(self, text: str) -> Tuple[float, ...]:
        features = self.tokenize(text)
        if not features:
            return tuple(0.0 for _ in range(self.dimensions))

        vector = [0.0] * self.dimensions
        for feature in features:
            index, sign = self._hash_feature(feature)
            vector[index] += sign * self._feature_weight(feature)

        norm = math.sqrt(sum(v * v for v in vector))
        if norm <= 0.0:
            return tuple(0.0 for _ in range(self.dimensions))
        return tuple(v / norm for v in vector)

    def embed(self, text: str) -> List[float]:
        return list(self._embed_cached(self.normalize_text(text)))

    def embed_many(self, texts: Iterable[str]) -> List[List[float]]:
        return [self.embed(text) for text in texts]

    @staticmethod
    def cosine_similarity(a: Sequence[float], b: Sequence[float]) -> float:
        if not a or not b or len(a) != len(b):
            return 0.0
        dot = sum(x * y for x, y in zip(a, b))
        na = math.sqrt(sum(x * x for x in a))
        nb = math.sqrt(sum(y * y for y in b))
        if na == 0.0 or nb == 0.0:
            return 0.0
        return dot / (na * nb)
