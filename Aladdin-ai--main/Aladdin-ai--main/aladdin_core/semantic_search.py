"""Semantic search engine for Smart Memory Part 3."""

from __future__ import annotations

from typing import Any, Dict, Iterable, List, Optional

from .embedding_manager import EmbeddingManager
from .memory_ranking import MemoryRanker
from .vector_store import VectorStore


class SemanticSearchEngine:
    """Indexes memory text and retrieves semantically similar records."""

    def __init__(
        self,
        embedding_manager: EmbeddingManager,
        vector_store: VectorStore,
        ranker: Optional[MemoryRanker] = None,
        *,
        namespace: str = "memory",
        default_limit: int = 5,
        min_similarity: float = 0.12,
    ):
        self.embedding_manager = embedding_manager
        self.vector_store = vector_store
        self.ranker = ranker or MemoryRanker()
        self.namespace = namespace
        self.default_limit = max(int(default_limit or 5), 1)
        self.min_similarity = float(min_similarity or 0.0)

    def index_memory(
        self,
        record_id: str,
        text: str,
        *,
        source_type: str = "general",
        source_id: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
        importance: float = 0.0,
        namespace: Optional[str] = None,
    ) -> str:
        embedding = self.embedding_manager.embed(text)
        return self.vector_store.upsert(
            record_id,
            text,
            embedding,
            namespace=namespace or self.namespace,
            source_type=source_type,
            source_id=source_id,
            metadata=metadata,
            importance=importance,
        )

    def bulk_index(
        self,
        records: Iterable[Dict[str, Any]],
        *,
        namespace: Optional[str] = None,
    ) -> int:
        count = 0
        for item in records:
            self.index_memory(
                item["record_id"],
                item["text"],
                source_type=item.get("source_type", "general"),
                source_id=item.get("source_id"),
                metadata=item.get("metadata"),
                importance=float(item.get("importance", 0.0)),
                namespace=namespace or item.get("namespace") or self.namespace,
            )
            count += 1
        return count

    def delete_memory(self, record_id: str) -> bool:
        return self.vector_store.delete(record_id)

    def rebuild(
        self,
        records: Iterable[Dict[str, Any]],
        *,
        namespace: Optional[str] = None,
    ) -> int:
        target_namespace = namespace or self.namespace
        self.vector_store.clear_namespace(target_namespace)
        return self.bulk_index(records, namespace=target_namespace)

    def search(
        self,
        query: str,
        *,
        limit: Optional[int] = None,
        namespace: Optional[str] = None,
        source_types: Optional[List[str]] = None,
        filters: Optional[Dict[str, Any]] = None,
        min_similarity: Optional[float] = None,
    ) -> List[Dict[str, Any]]:
        if not (query or "").strip():
            return []
        effective_filters = dict(filters or {})
        if source_types:
            effective_filters["source_type"] = source_types
        raw_results = self.vector_store.search(
            self.embedding_manager.embed(query),
            top_k=max(
                int(limit or self.default_limit) * 3, int(limit or self.default_limit)
            ),
            namespace=namespace or self.namespace,
            filters=effective_filters or None,
            min_similarity=(
                self.min_similarity if min_similarity is None else float(min_similarity)
            ),
        )
        return self.ranker.rank(
            raw_results,
            query=query,
            top_k=limit or self.default_limit,
        )
