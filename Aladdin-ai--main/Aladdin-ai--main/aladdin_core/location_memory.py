"""Location Memory — remember home, office and other named places.

Part of Phase 3 — Smart Memory Part 2.
"""

from __future__ import annotations

import logging
import math
import sqlite3
import uuid
from pathlib import Path
from typing import Any, Dict, List, Optional

log = logging.getLogger(__name__)


# Well-known canonical labels handled specially
SPECIAL_LABELS = {"home", "office", "work"}


class LocationMemory:
    """Stores user-named locations.

    Schema:
      locations: id, label (unique, lowercase), display_name, address, latitude,
                 longitude, category, notes, visit_count, is_favorite,
                 created_at, updated_at
    """

    def __init__(self, db_path: str):
        self.db_path = db_path
        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(str(db_path), check_same_thread=False)
        self._db.execute("PRAGMA foreign_keys = ON")
        self._db.row_factory = sqlite3.Row
        self._init_schema()

    def _init_schema(self) -> None:
        self._db.execute("""
            CREATE TABLE IF NOT EXISTS locations (
                id              TEXT PRIMARY KEY,
                label           TEXT NOT NULL UNIQUE,
                display_name    TEXT,
                address         TEXT,
                latitude        REAL,
                longitude       REAL,
                category        TEXT DEFAULT 'custom',
                notes           TEXT,
                visit_count     INTEGER DEFAULT 0,
                is_favorite     INTEGER DEFAULT 0,
                created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP
            )
            """)
        self._db.execute(
            "CREATE INDEX IF NOT EXISTS locations_category_idx "
            "ON locations(category)"
        )
        self._db.execute(
            "CREATE INDEX IF NOT EXISTS locations_favorite_idx "
            "ON locations(is_favorite)"
        )
        self._db.commit()

    # ------------------------------------------------------------------
    # CRUD
    # ------------------------------------------------------------------
    def save_location(
        self,
        label: str,
        address: Optional[str] = None,
        latitude: Optional[float] = None,
        longitude: Optional[float] = None,
        display_name: Optional[str] = None,
        category: str = "custom",
        notes: Optional[str] = None,
        is_favorite: bool = False,
    ) -> str:
        """Save or update a named location. Returns its id."""
        lbl = (label or "").strip().lower()
        if not lbl:
            raise ValueError("Location label required")

        if lbl in SPECIAL_LABELS:
            category = "home" if lbl == "home" else "office"

        existing = self._db.execute(
            "SELECT id FROM locations WHERE label = ?", (lbl,)
        ).fetchone()
        if existing:
            self._db.execute(
                "UPDATE locations SET display_name = COALESCE(?, display_name), "
                "address = COALESCE(?, address), "
                "latitude = COALESCE(?, latitude), "
                "longitude = COALESCE(?, longitude), "
                "category = ?, "
                "notes = COALESCE(?, notes), "
                "is_favorite = ?, "
                "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                (
                    display_name,
                    address,
                    latitude,
                    longitude,
                    category,
                    notes,
                    int(bool(is_favorite)),
                    existing["id"],
                ),
            )
            self._db.commit()
            return existing["id"]

        lid = str(uuid.uuid4())
        self._db.execute(
            "INSERT INTO locations(id, label, display_name, address, "
            "latitude, longitude, category, notes, is_favorite) "
            "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (
                lid,
                lbl,
                display_name or label,
                address,
                latitude,
                longitude,
                category,
                notes,
                int(bool(is_favorite)),
            ),
        )
        self._db.commit()
        log.info("Location saved: %s (%s)", label, lid)
        return lid

    # Convenience wrappers ------------------------------------------------
    def set_home(self, **kwargs: Any) -> str:
        return self.save_location("home", category="home", **kwargs)

    def set_office(self, **kwargs: Any) -> str:
        return self.save_location("office", category="office", **kwargs)

    def get_home(self) -> Optional[Dict[str, Any]]:
        return self.get_location("home")

    def get_office(self) -> Optional[Dict[str, Any]]:
        return self.get_location("office")

    # General accessors --------------------------------------------------
    def get_location(self, label: str) -> Optional[Dict[str, Any]]:
        row = self._db.execute(
            "SELECT * FROM locations WHERE label = ?", ((label or "").lower(),)
        ).fetchone()
        return dict(row) if row else None

    def get_by_id(self, location_id: str) -> Optional[Dict[str, Any]]:
        row = self._db.execute(
            "SELECT * FROM locations WHERE id = ?", (location_id,)
        ).fetchone()
        return dict(row) if row else None

    def update_location(self, label: str, **fields: Any) -> bool:
        loc = self.get_location(label)
        if not loc:
            return False
        allowed = {
            "display_name",
            "address",
            "latitude",
            "longitude",
            "category",
            "notes",
            "is_favorite",
        }
        updates: List[str] = []
        values: List[Any] = []
        for k, v in fields.items():
            if k not in allowed:
                continue
            if k == "is_favorite":
                v = int(bool(v))
            updates.append(f"{k} = ?")
            values.append(v)
        if not updates:
            return False
        updates.append("updated_at = CURRENT_TIMESTAMP")
        values.append(loc["id"])
        self._db.execute(
            f"UPDATE locations SET {', '.join(updates)} WHERE id = ?",
            values,
        )
        self._db.commit()
        return True

    def delete_location(self, label: str) -> bool:
        loc = self.get_location(label)
        if not loc:
            return False
        self._db.execute("DELETE FROM locations WHERE id = ?", (loc["id"],))
        self._db.commit()
        log.info("Location deleted: %s", label)
        return True

    # ------------------------------------------------------------------
    # Frequently visited / favorites
    # ------------------------------------------------------------------
    def record_visit(self, label: str) -> bool:
        loc = self.get_location(label)
        if not loc:
            return False
        self._db.execute(
            "UPDATE locations SET visit_count = visit_count + 1, "
            "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
            (loc["id"],),
        )
        self._db.commit()
        return True

    def mark_favorite(self, label: str, favorite: bool = True) -> bool:
        return self.update_location(label, is_favorite=favorite)

    def frequently_visited(self, limit: int = 5) -> List[Dict[str, Any]]:
        rows = self._db.execute(
            "SELECT * FROM locations WHERE visit_count > 0 "
            "ORDER BY visit_count DESC, updated_at DESC LIMIT ?",
            (limit,),
        ).fetchall()
        return [dict(r) for r in rows]

    def favorites(self) -> List[Dict[str, Any]]:
        rows = self._db.execute(
            "SELECT * FROM locations WHERE is_favorite = 1 " "ORDER BY label ASC"
        ).fetchall()
        return [dict(r) for r in rows]

    # ------------------------------------------------------------------
    # Searching / listing
    # ------------------------------------------------------------------
    def list_locations(self, category: Optional[str] = None) -> List[Dict[str, Any]]:
        if category:
            rows = self._db.execute(
                "SELECT * FROM locations WHERE category = ? " "ORDER BY label ASC",
                (category,),
            ).fetchall()
        else:
            rows = self._db.execute(
                "SELECT * FROM locations ORDER BY label ASC"
            ).fetchall()
        return [dict(r) for r in rows]

    def search(self, query: str) -> List[Dict[str, Any]]:
        like = f"%{query.lower()}%"
        rows = self._db.execute(
            "SELECT * FROM locations WHERE LOWER(label) LIKE ? "
            "OR LOWER(IFNULL(display_name,'')) LIKE ? "
            "OR LOWER(IFNULL(address,'')) LIKE ? "
            "OR LOWER(IFNULL(notes,'')) LIKE ? "
            "ORDER BY visit_count DESC, label ASC",
            (like, like, like, like),
        ).fetchall()
        return [dict(r) for r in rows]

    # ------------------------------------------------------------------
    # Geo helpers
    # ------------------------------------------------------------------
    @staticmethod
    def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
        """Great-circle distance in kilometres."""
        r = 6371.0
        phi1, phi2 = math.radians(lat1), math.radians(lat2)
        dphi = math.radians(lat2 - lat1)
        dl = math.radians(lon2 - lon1)
        a = (
            math.sin(dphi / 2) ** 2
            + math.cos(phi1) * math.cos(phi2) * math.sin(dl / 2) ** 2
        )
        return 2 * r * math.asin(math.sqrt(a))

    def nearest(
        self, latitude: float, longitude: float, limit: int = 3
    ) -> List[Dict[str, Any]]:
        """Return the closest stored locations that have coordinates."""
        rows = self._db.execute(
            "SELECT * FROM locations WHERE latitude IS NOT NULL "
            "AND longitude IS NOT NULL"
        ).fetchall()
        out: List[Dict[str, Any]] = []
        for r in rows:
            d = self.haversine_km(latitude, longitude, r["latitude"], r["longitude"])
            entry = dict(r)
            entry["distance_km"] = round(d, 3)
            out.append(entry)
        out.sort(key=lambda x: x["distance_km"])
        return out[:limit]

    # ------------------------------------------------------------------
    # Stats / utility
    # ------------------------------------------------------------------
    def stats(self) -> Dict[str, Any]:
        total = self._db.execute("SELECT COUNT(*) AS c FROM locations").fetchone()["c"]
        favs = self._db.execute(
            "SELECT COUNT(*) AS c FROM locations WHERE is_favorite = 1"
        ).fetchone()["c"]
        return {
            "total": total,
            "favorites": favs,
            "has_home": self.get_home() is not None,
            "has_office": self.get_office() is not None,
        }

    def clear(self) -> None:
        self._db.execute("DELETE FROM locations")
        self._db.commit()
        log.warning("All location memory cleared")

    def close(self) -> None:
        try:
            self._db.close()
        except Exception:  # pragma: no cover
            pass
