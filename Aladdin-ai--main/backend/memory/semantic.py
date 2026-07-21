"""
Semantic memory using Sentence Transformers + FAISS.
Task 19: FAISS index saved to disk and restored on startup.
"""
from __future__ import annotations

import json
import logging
import sqlite3
import time
import uuid
from pathlib import Path
from typing import Any, Dict, List, Optional

import numpy as np

log = logging.getLogger(__name__)

try:
    from sentence_transformers import SentenceTransformer
    _ST_AVAILABLE = True
except ImportError:
    _ST_AVAILABLE = False
    log.warning("sentence-transformers not installed — semantic search disabled")

try:
    import faiss
    _FAISS_AVAILABLE = True
except ImportError:
    _FAISS_AVAILABLE = False
    log.warning("faiss not installed — semantic search disabled")


class SemanticMemory:
    """Stores text chunks with embeddings for semantic similarity search.
    Task 19: FAISS index persisted to <db_path>.faiss + id map to <db_path>.ids.json
    """

    def __init__(self, db_path: Path, model_name: str = "all-MiniLM-L6-v2") -> None:
        self._db = db_path
        self._db.parent.mkdir(parents=True, exist_ok=True)
        self._model: Optional[Any] = None
        self._index: Optional[Any] = None
        self._id_map: List[str] = []
        self._model_name = model_name
        # Task 19: Paths for persisted FAISS index + id map
        self._index_path = Path(str(db_path) + ".faiss")
        self._idmap_path = Path(str(db_path) + ".ids.json")
        self._init_db()
        if _ST_AVAILABLE and _FAISS_AVAILABLE:
            self._load_model()
            self._load_or_rebuild_index()

    def _conn(self):
        conn = sqlite3.connect(str(self._db))
        conn.execute("PRAGMA foreign_keys = ON")
        conn.row_factory = sqlite3.Row
        return conn

    def _init_db(self):
        with self._conn() as conn:
            conn.execute("""
                CREATE TABLE IF NOT EXISTS semantic (
                    id TEXT PRIMARY KEY,
                    content TEXT NOT NULL,
                    embedding BLOB,
                    metadata TEXT DEFAULT '{}',
                    category TEXT DEFAULT 'general',
                    timestamp REAL NOT NULL
                )
            """)

    def _load_model(self):
        try:
            self._model = SentenceTransformer(self._model_name)
            log.info("Loaded sentence transformer: %s", self._model_name)
        except Exception as exc:
            log.error("Could not load sentence transformer: %s", exc)

    def _embed(self, text: str) -> Optional[np.ndarray]:
        if self._model is None:
            return None
        try:
            vec = self._model.encode([text], normalize_embeddings=True)
            return vec[0].astype(np.float32)
        except Exception as exc:
            log.error("Embedding error: %s", exc)
            return None

    # ── Task 19: FAISS index persistence ──────────────────────────────────────

    def _save_index(self):
        """Task 19: Persist FAISS index and id-map to disk."""
        if not _FAISS_AVAILABLE or self._index is None:
            return
        try:
            faiss.write_index(self._index, str(self._index_path))
            self._idmap_path.write_text(json.dumps(self._id_map))
            log.debug("FAISS index saved: %d vectors → %s", self._index.ntotal, self._index_path)
        except Exception as exc:
            log.warning("FAISS index save failed: %s", exc)

    def _load_or_rebuild_index(self):
        """Task 19: Try to restore FAISS index from disk; fall back to DB rebuild."""
        if not (_FAISS_AVAILABLE and _ST_AVAILABLE):
            return

        if self._index_path.exists() and self._idmap_path.exists():
            try:
                self._index = faiss.read_index(str(self._index_path))
                self._id_map = json.loads(self._idmap_path.read_text())
                log.info("FAISS index restored from disk: %d vectors", self._index.ntotal)
                return
            except Exception as exc:
                log.warning("FAISS index load failed, rebuilding from DB: %s", exc)

        self._rebuild_index()

    def _rebuild_index(self):
        if not (_FAISS_AVAILABLE and _ST_AVAILABLE):
            return
        with self._conn() as conn:
            rows = conn.execute("SELECT id, embedding FROM semantic WHERE embedding IS NOT NULL").fetchall()
        if not rows:
            dim = self._model.get_sentence_embedding_dimension() if self._model else 384
            self._index = faiss.IndexFlatIP(dim)
            self._id_map = []
            return
        ids, vecs = [], []
        for r in rows:
            ids.append(r["id"])
            arr = np.frombuffer(r["embedding"], dtype=np.float32)
            vecs.append(arr)
        dim = vecs[0].shape[0]
        self._index = faiss.IndexFlatIP(dim)
        self._index.add(np.stack(vecs))
        self._id_map = ids
        log.info("Rebuilt FAISS index with %d vectors", len(ids))
        self._save_index()  # Task 19: save after rebuild

    # ── CRUD ─────────────────────────────────────────────────────────────────

    def add(self, content: str, metadata: Optional[Dict] = None, category: str = "general") -> str:
        rid = str(uuid.uuid4())
        embedding = self._embed(content)
        emb_bytes = embedding.tobytes() if embedding is not None else None
        with self._conn() as conn:
            conn.execute(
                "INSERT INTO semantic (id,content,embedding,metadata,category,timestamp) VALUES (?,?,?,?,?,?)",
                (rid, content, emb_bytes, json.dumps(metadata or {}), category, time.time()),
            )
        if embedding is not None and self._index is not None:
            self._index.add(np.array([embedding]))
            self._id_map.append(rid)
            self._save_index()  # Task 19: persist after each add
        return rid

    def search(
        self,
        query: str,
        top_k: int = 5,
        min_similarity: float = 0.12,
        category: Optional[str] = None,
    ) -> List[Dict]:
        if not (_FAISS_AVAILABLE and _ST_AVAILABLE) or self._index is None or self._index.ntotal == 0:
            return self._fallback_search(query, top_k)
        q_vec = self._embed(query)
        if q_vec is None:
            return self._fallback_search(query, top_k)
        scores, indices = self._index.search(np.array([q_vec]), min(top_k * 2, self._index.ntotal))
        results = []
        with self._conn() as conn:
            for score, idx in zip(scores[0], indices[0]):
                if idx < 0 or float(score) < min_similarity:
                    continue
                if idx >= len(self._id_map):
                    continue
                row_id = self._id_map[idx]
                row = conn.execute("SELECT * FROM semantic WHERE id=?", (row_id,)).fetchone()
                if row and (category is None or row["category"] == category):
                    results.append({
                        "id": row["id"],
                        "content": row["content"],
                        "similarity": float(score),
                        "metadata": json.loads(row["metadata"]),
                        "category": row["category"],
                    })
                if len(results) >= top_k:
                    break
        return results

    def _fallback_search(self, query: str, top_k: int) -> List[Dict]:
        """Simple keyword fallback when FAISS/ST unavailable."""
        q_lower = query.lower()
        with self._conn() as conn:
            rows = conn.execute("SELECT * FROM semantic ORDER BY timestamp DESC LIMIT 200").fetchall()
        scored = []
        for r in rows:
            score = sum(1 for w in q_lower.split() if w in r["content"].lower())
            if score > 0:
                scored.append((score, r))
        scored.sort(key=lambda x: x[0], reverse=True)
        return [
            {
                "id": r["id"],
                "content": r["content"],
                "similarity": s / 10.0,
                "metadata": json.loads(r["metadata"]),
                "category": r["category"],
            }
            for s, r in scored[:top_k]
        ]

    def delete(self, memory_id: str) -> bool:
        with self._conn() as conn:
            rows = conn.execute("DELETE FROM semantic WHERE id=?", (memory_id,)).rowcount
        if rows:
            self._rebuild_index()
        return rows > 0

    def clear(self, category: Optional[str] = None) -> int:
        with self._conn() as conn:
            if category:
                count = conn.execute("DELETE FROM semantic WHERE category=?", (category,)).rowcount
            else:
                count = conn.execute("DELETE FROM semantic").rowcount
        self._rebuild_index()
        return count
