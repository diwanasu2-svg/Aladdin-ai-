"""Phase 11.6 — Smart Recommendation Engine.

Analyses user history to recommend:
  apps · music · videos · books · websites · tasks · shopping

Uses collaborative filtering (scikit-learn) with frequency-based fallback.
Learns from user feedback (thumbs up/down).
"""
from __future__ import annotations
import asyncio
import json
import logging
import joblib
import sqlite3
import time
from contextlib import contextmanager
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

from .config import (
    RECO_DB, RECO_MODEL_PATH, RECO_MAX_ITEMS, RECO_MIN_INTERACTIONS,
)

log = logging.getLogger(__name__)

ITEM_TYPES = ["app", "music", "video", "book", "website", "task", "shopping"]


@dataclass
class RecoItem:
    item_id: str
    item_type: str
    title: str
    metadata: Dict = field(default_factory=dict)
    score: float = 0.0
    reason: str = ""


@dataclass
class Interaction:
    user_id: str
    item_id: str
    item_type: str
    action: str          # "view", "open", "like", "dislike", "purchase", "complete"
    weight: float = 1.0
    timestamp: float = field(default_factory=time.time)
    context: Dict = field(default_factory=dict)


class RecommendationEngine:
    """Context-aware, feedback-learning recommendation engine."""

    def __init__(self):
        self._init_db()
        self._models: Dict[str, Any] = {}

    # ── Database ──────────────────────────────────────────────────────────────
    @contextmanager
    def _db(self):
        conn = sqlite3.connect(RECO_DB)
        conn.execute("PRAGMA foreign_keys = ON")
        conn.row_factory = sqlite3.Row
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()

    def _init_db(self):
        with self._db() as db:
            db.execute("""
                CREATE TABLE IF NOT EXISTS interactions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id TEXT, item_id TEXT, item_type TEXT,
                    action TEXT, weight REAL DEFAULT 1.0,
                    timestamp REAL, context TEXT DEFAULT '{}'
                )
            """)
            db.execute("""
                CREATE TABLE IF NOT EXISTS items (
                    item_id TEXT PRIMARY KEY,
                    item_type TEXT, title TEXT,
                    metadata TEXT DEFAULT '{}'
                )
            """)
            db.execute("""
                CREATE TABLE IF NOT EXISTS feedback (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id TEXT, item_id TEXT,
                    score REAL DEFAULT 0.0,
                    timestamp REAL
                )
            """)

    # ── Record interaction ────────────────────────────────────────────────────
    def record(self, user_id: str, item_id: str, item_type: str,
               action: str, title: str = "", metadata: Optional[Dict] = None,
               context: Optional[Dict] = None):
        action_weights = {
            "purchase": 5.0, "like": 3.0, "complete": 2.5,
            "open": 1.5, "view": 1.0, "dislike": -2.0,
        }
        weight = action_weights.get(action, 1.0)

        with self._db() as db:
            db.execute(
                "INSERT OR IGNORE INTO items(item_id,item_type,title,metadata) VALUES(?,?,?,?)",
                (item_id, item_type, title, json.dumps(metadata or {}))
            )
            db.execute(
                "INSERT INTO interactions(user_id,item_id,item_type,action,weight,timestamp,context)"
                " VALUES(?,?,?,?,?,?,?)",
                (user_id, item_id, item_type, action, weight,
                 time.time(), json.dumps(context or {}))
            )
        log.debug("Interaction recorded: %s → %s (%s)", user_id, item_id, action)

    # ── Feedback ──────────────────────────────────────────────────────────────
    def feedback(self, user_id: str, item_id: str, score: float):
        """score: +1.0 = liked, -1.0 = disliked, 0.5 = neutral."""
        with self._db() as db:
            db.execute(
                "INSERT INTO feedback(user_id,item_id,score,timestamp) VALUES(?,?,?,?)",
                (user_id, item_id, score, time.time())
            )
        log.debug("Feedback recorded: user=%s item=%s score=%+.1f", user_id, item_id, score)

    # ── Frequency-based recommendations ──────────────────────────────────────
    def _frequency_recommend(self, user_id: str, item_type: Optional[str],
                               limit: int) -> List[RecoItem]:
        with self._db() as db:
            type_filter = "AND i.item_type=?" if item_type else ""
            params: Tuple = (user_id, item_type, limit) if item_type else (user_id, limit)
            rows = db.execute(f"""
                SELECT i.item_id, i.item_type, i.title, i.metadata,
                       SUM(inter.weight) AS score
                FROM interactions inter
                JOIN items i ON i.item_id = inter.item_id
                WHERE inter.user_id=? {type_filter}
                GROUP BY inter.item_id
                ORDER BY score DESC
                LIMIT ?
            """, params).fetchall()

        items = []
        for r in rows:
            meta = {}
            try:
                meta = json.loads(r["metadata"])
            except Exception:
                pass
            items.append(RecoItem(
                item_id=r["item_id"], item_type=r["item_type"],
                title=r["title"], metadata=meta,
                score=round(float(r["score"]), 2),
                reason="Based on your usage history"
            ))
        return items

    # ── Collaborative filtering ───────────────────────────────────────────────
    def train(self, min_users: int = 3) -> bool:
        """Train matrix factorisation model (SVD) from interaction matrix."""
        try:
            from sklearn.decomposition import TruncatedSVD
            from sklearn.preprocessing import LabelEncoder
            import numpy as np
        except ImportError:
            log.warning("scikit-learn not available — using frequency-only recommendations")
            return False

        with self._db() as db:
            rows = db.execute(
                "SELECT user_id, item_id, SUM(weight) as score FROM interactions GROUP BY user_id, item_id"
            ).fetchall()

        if len(rows) < RECO_MIN_INTERACTIONS:
            return False

        users = list({r["user_id"] for r in rows})
        items = list({r["item_id"] for r in rows})
        if len(users) < min_users:
            return False

        ue = LabelEncoder().fit(users)
        ie = LabelEncoder().fit(items)
        R = np.zeros((len(users), len(items)))
        for r in rows:
            u_idx = ue.transform([r["user_id"]])[0]
            i_idx = ie.transform([r["item_id"]])[0]
            R[u_idx, i_idx] = float(r["score"])

        n_components = min(20, R.shape[1] - 1)
        if n_components < 1:
            return False

        svd = TruncatedSVD(n_components=n_components, random_state=42)
        R_approx = svd.fit_transform(R)
        model = {"svd": svd, "ue": ue, "ie": ie, "R": R, "R_approx": R_approx}
        self._models["global"] = model

        joblib.dump(model, RECO_MODEL_PATH)
        log.info("Recommendation model trained: %d users, %d items", len(users), len(items))
        return True

    def _collab_recommend(self, user_id: str, limit: int) -> List[RecoItem]:
        model = self._models.get("global")
        if not model:
            try:
                model  = joblib.load(RECO_MODEL_PATH)
                self._models["global"] = model
            except Exception:
                return []

        try:
            import numpy as np
            ue, ie = model["ue"], model["ie"]
            if user_id not in ue.classes_:
                return []
            u_idx = ue.transform([user_id])[0]
            R_approx = model["R_approx"]
            R = model["R"]
            # Reconstruct full row
            svd = model["svd"]
            user_vec = R_approx[u_idx]
            scores = svd.components_.T @ svd.components_ @ R[u_idx]
            # Exclude already-interacted items
            already = np.where(R[u_idx] > 0)[0]
            scores[already] = -999
            top_idx = scores.argsort()[-limit:][::-1]
            items = []
            for idx in top_idx:
                if scores[idx] <= 0:
                    continue
                item_id = ie.inverse_transform([idx])[0]
                with self._db() as db:
                    row = db.execute("SELECT * FROM items WHERE item_id=?", (item_id,)).fetchone()
                if row:
                    items.append(RecoItem(
                        item_id=item_id, item_type=row["item_type"],
                        title=row["title"],
                        score=round(float(scores[idx]), 2),
                        reason="Collaborative filtering (similar users)"
                    ))
            return items
        except Exception as e:
            log.warning("Collab filtering error: %s", e)
            return []

    # ── Context filtering ─────────────────────────────────────────────────────
    def _apply_context_filter(self, items: List[RecoItem],
                               context: Optional[Dict]) -> List[RecoItem]:
        if not context:
            return items
        time_of_day = context.get("time_of_day", "")
        activity = context.get("activity", "")

        # Remove disliked items
        with self._db() as db:
            downvoted = {
                r["item_id"]
                for r in db.execute("SELECT item_id FROM feedback WHERE score < 0").fetchall()
            }

        filtered = [i for i in items if i.item_id not in downvoted]

        # Contextual boosting
        for item in filtered:
            if time_of_day == "morning" and item.item_type == "task":
                item.score += 0.3
            if activity == "commute" and item.item_type in ("music", "podcast"):
                item.score += 0.5
            if time_of_day == "night" and item.item_type == "video":
                item.score += 0.2

        return sorted(filtered, key=lambda x: x.score, reverse=True)

    # ── Main recommendation API ───────────────────────────────────────────────
    async def recommend(self, user_id: str = "default",
                         item_type: Optional[str] = None,
                         context: Optional[Dict] = None,
                         limit: int = RECO_MAX_ITEMS) -> List[RecoItem]:
        # Try collaborative first, fall back to frequency
        collab = self._collab_recommend(user_id, limit * 2)
        freq = self._frequency_recommend(user_id, item_type, limit * 2)

        # Merge: prefer collab, deduplicate
        seen = set()
        merged = []
        for item in collab + freq:
            if item.item_id not in seen:
                merged.append(item)
                seen.add(item.item_id)

        filtered = self._apply_context_filter(merged, context)
        result = filtered[:limit]
        log.debug("Recommendations for %s: %d items", user_id, len(result))
        return result

    async def recommend_apps(self, user_id: str, context: Optional[Dict] = None) -> List[RecoItem]:
        return await self.recommend(user_id, item_type="app", context=context)

    async def recommend_content(self, user_id: str, content_type: str,
                                 context: Optional[Dict] = None) -> List[RecoItem]:
        return await self.recommend(user_id, item_type=content_type, context=context)

    async def get_task_suggestions(self, user_id: str,
                                    context: Optional[Dict] = None) -> List[str]:
        items = await self.recommend(user_id, item_type="task", context=context, limit=5)
        return [f"• {item.title} ({item.reason})" for item in items]
