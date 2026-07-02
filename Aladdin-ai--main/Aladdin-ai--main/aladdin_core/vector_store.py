"""SQLite-backed vector database for Smart Memory Part 3."""

from __future__ import annotations

import json
import logging
import sqlite3
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence

log = logging.getLogger(__name__)


class VectorStore:
    """Persistent vector store using SQLite rows + Python cosine search."""

    def __init__(self, db_path: str):
        self.db_path = db_path
        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(str(db_path), check_same_thread=False)
        self._db.execute("PRAGMA foreign_keys = ON")
        self._db.row_factory = sqlite3.Row
        self._init_schema()

    def _init_schema(self) -> None:
        self._db.execute("""
            CREATE TABLE IF NOT EXISTS memory_vectors (
                record_id    TEXT PRIMARY KEY,
                namespace    TEXT NOT NULL DEFAULT 'memory',
                source_type  TEXT NOT NULL DEFAULT 'general',
                source_id    TEXT,
                text         TEXT NOT NULL,
                metadata     TEXT DEFAULT '{}',
                embedding    TEXT NOT NULL,
                importance   REAL DEFAULT 0.0,
                created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP
            )
            """)
        self._db.execute(
            "CREATE INDEX IF NOT EXISTS mv_namespace_idx ON memory_vectors(namespace)"
        )
        self._db.execute(
            "CREATE INDEX IF NOT EXISTS mv_source_type_idx ON memory_vectors(source_type)"
        )
        self._db.execute(
            "CREATE INDEX IF NOT EXISTS mv_source_id_idx ON memory_vectors(source_id)"
        )
        self._db.commit()

    def upsert(
        self,
        record_id: str,
        text: str,
        embedding: Sequence[float],
        *,
        namespace: str = "memory",
        source_type: str = "general",
        source_id: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
        importance: float = 0.0,
    ) -> str:
        self._db.execute(
            """
            INSERT INTO memory_vectors(
                record_id, namespace, source_type, source_id,
                text, metadata, embedding, importance
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(record_id) DO UPDATE SET
                namespace=excluded.namespace,
                source_type=excluded.source_type,
                source_id=excluded.source_id,
                text=excluded.text,
                metadata=excluded.metadata,
                embedding=excluded.embedding,
                importance=excluded.importance,
                updated_at=CURRENT_TIMESTAMP
            """,
            (
                record_id,
                namespace,
                source_type,
                source_id,
                text,
                json.dumps(metadata or {}, ensure_ascii=False, sort_keys=True),
                json.dumps(list(embedding)),
                float(importance or 0.0),
            ),
        )
        self._db.commit()
        return record_id

    def get(self, record_id: str) -> Optional[Dict[str, Any]]:
        row = self._db.execute(
            "SELECT * FROM memory_vectors WHERE record_id = ?", (record_id,)
        ).fetchone()
        return self._row_to_dict(row) if row else None

    def delete(self, record_id: str) -> bool:
        cur = self._db.execute(
            "DELETE FROM memory_vectors WHERE record_id = ?", (record_id,)
        )
        self._db.commit()
        return cur.rowcount > 0

    def clear_namespace(self, namespace: str = "memory") -> None:
        self._db.execute("DELETE FROM memory_vectors WHERE namespace = ?", (namespace,))
        self._db.commit()

    def count(self, namespace: Optional[str] = None) -> int:
        if namespace:
            row = self._db.execute(
                "SELECT COUNT(*) AS c FROM memory_vectors WHERE namespace = ?",
                (namespace,),
            ).fetchone()
        else:
            row = self._db.execute(
                "SELECT COUNT(*) AS c FROM memory_vectors"
            ).fetchone()
        return int(row["c"])

    def list_records(
        self,
        namespace: Optional[str] = None,
        source_type: Optional[str] = None,
    ) -> List[Dict[str, Any]]:
        query = "SELECT * FROM memory_vectors"
        clauses: List[str] = []
        params: List[Any] = []
        if namespace:
            clauses.append("namespace = ?")
            params.append(namespace)
        if source_type:
            clauses.append("source_type = ?")
            params.append(source_type)
        if clauses:
            query += " WHERE " + " AND ".join(clauses)
        query += " ORDER BY updated_at DESC"
        rows = self._db.execute(query, params).fetchall()
        return [self._row_to_dict(row) for row in rows]

    def search(
        self,
        query_embedding: Sequence[float],
        *,
        top_k: int = 5,
        namespace: str = "memory",
        filters: Optional[Dict[str, Any]] = None,
        min_similarity: float = 0.0,
    ) -> List[Dict[str, Any]]:
        rows = self._db.execute(
            "SELECT * FROM memory_vectors WHERE namespace = ?",
            (namespace,),
        ).fetchall()
        results: List[Dict[str, Any]] = []
        for row in rows:
            item = self._row_to_dict(row)
            if filters and not self._matches_filters(item, filters):
                continue
            similarity = self._cosine_similarity(query_embedding, item["embedding"])
            if similarity < float(min_similarity or 0.0):
                continue
            item["similarity"] = similarity
            results.append(item)
        results.sort(
            key=lambda x: (
                float(x.get("similarity", 0.0)),
                float(x.get("importance", 0.0)),
            ),
            reverse=True,
        )
        return results[: max(int(top_k or 1), 1)]

    def stats(self, namespace: Optional[str] = None) -> Dict[str, Any]:
        records = self.list_records(namespace=namespace)
        by_type: Dict[str, int] = {}
        for item in records:
            source_type = item.get("source_type", "general")
            by_type[source_type] = by_type.get(source_type, 0) + 1
        return {
            "count": len(records),
            "by_source_type": by_type,
            "namespace": namespace or "all",
        }

    @staticmethod
    def _matches_filters(item: Dict[str, Any], filters: Dict[str, Any]) -> bool:
        metadata = item.get("metadata") or {}
        for key, expected in filters.items():
            actual = item.get(key, metadata.get(key))
            if isinstance(expected, (list, tuple, set)):
                if actual not in expected:
                    return False
            elif actual != expected:
                return False
        return True

    @staticmethod
    def _cosine_similarity(a: Sequence[float], b: Sequence[float]) -> float:
        if not a or not b or len(a) != len(b):
            return 0.0
        dot = sum(x * y for x, y in zip(a, b))
        na = sum(x * x for x in a) ** 0.5
        nb = sum(y * y for y in b) ** 0.5
        if na == 0.0 or nb == 0.0:
            return 0.0
        return dot / (na * nb)

    @staticmethod
    def _row_to_dict(row: sqlite3.Row) -> Dict[str, Any]:
        item = dict(row)
        item["metadata"] = json.loads(item.get("metadata") or "{}")
        item["embedding"] = json.loads(item.get("embedding") or "[]")
        return item
