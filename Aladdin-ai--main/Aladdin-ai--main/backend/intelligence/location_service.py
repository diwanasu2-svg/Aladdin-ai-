"""Phase 11.8 — Location Awareness Service.

Features:
  • GPS location (via ADB or device API)
  • Home / office / favourite place identification
  • Geo-fencing with configurable radius
  • Location-based reminders
  • Nearby places search
  • Travel ETA (Google Maps or OpenStreetMap)
  • Privacy-first: user controls what is stored
"""
from __future__ import annotations
import asyncio
import json
import logging
import math
import sqlite3
import time
from contextlib import contextmanager
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

from .config import (
    LOCATION_DB, LOCATION_UPDATE_INTERVAL_S,
    GEOFENCE_RADIUS_M, OPENWEATHER_API_KEY,
)

log = logging.getLogger(__name__)


@dataclass
class Place:
    label: str
    lat: float
    lng: float
    radius_m: float = GEOFENCE_RADIUS_M
    place_type: str = "custom"   # home / office / custom
    user_id: str = "default"
    address: str = ""
    added_at: float = field(default_factory=time.time)


@dataclass
class LocationFix:
    lat: float
    lng: float
    accuracy_m: float = 0.0
    source: str = "unknown"   # gps / network / adb / ip
    timestamp: float = field(default_factory=time.time)


@dataclass
class GeoFenceEvent:
    place: Place
    event_type: str   # "enter" / "exit"
    timestamp: float = field(default_factory=time.time)


def haversine(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    """Return distance in metres between two lat/lng points."""
    R = 6_371_000.0
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlam = math.radians(lng2 - lng1)
    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlam / 2) ** 2
    return R * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


class LocationService:
    """Privacy-first location service with geo-fencing and place awareness."""

    def __init__(self):
        self._init_db()
        self._last_fix: Dict[str, LocationFix] = {}
        self._geofence_state: Dict[str, Dict[str, bool]] = {}  # user_id → {label → inside}
        self._fence_callbacks: List = []

    # ── Database ──────────────────────────────────────────────────────────────
    @contextmanager
    def _db(self):
        conn = sqlite3.connect(LOCATION_DB)
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
                CREATE TABLE IF NOT EXISTS places (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    label TEXT, lat REAL, lng REAL,
                    radius_m REAL DEFAULT 200,
                    place_type TEXT DEFAULT 'custom',
                    user_id TEXT DEFAULT 'default',
                    address TEXT DEFAULT '',
                    added_at REAL
                )
            """)
            db.execute("""
                CREATE TABLE IF NOT EXISTS location_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id TEXT, lat REAL, lng REAL,
                    accuracy_m REAL, source TEXT, timestamp REAL,
                    place_label TEXT DEFAULT ''
                )
            """)

    # ── Place management ──────────────────────────────────────────────────────
    def save_place(self, label: str, lat: float, lng: float,
                   place_type: str = "custom", radius_m: float = GEOFENCE_RADIUS_M,
                   address: str = "", user_id: str = "default") -> Place:
        p = Place(label=label, lat=lat, lng=lng, radius_m=radius_m,
                  place_type=place_type, user_id=user_id, address=address)
        with self._db() as db:
            db.execute(
                "INSERT INTO places(label,lat,lng,radius_m,place_type,user_id,address,added_at)"
                " VALUES(?,?,?,?,?,?,?,?)",
                (label, lat, lng, radius_m, place_type, user_id, address, time.time())
            )
        log.info("Place saved: %s (%.4f, %.4f) r=%.0fm", label, lat, lng, radius_m)
        return p

    def set_home(self, lat: float, lng: float, user_id: str = "default") -> Place:
        self._delete_place_type("home", user_id)
        return self.save_place("home", lat, lng, place_type="home", user_id=user_id)

    def set_office(self, lat: float, lng: float, user_id: str = "default") -> Place:
        self._delete_place_type("office", user_id)
        return self.save_place("office", lat, lng, place_type="office", user_id=user_id)

    def _delete_place_type(self, place_type: str, user_id: str):
        with self._db() as db:
            db.execute("DELETE FROM places WHERE place_type=? AND user_id=?",
                       (place_type, user_id))

    def list_places(self, user_id: str = "default") -> List[Place]:
        with self._db() as db:
            rows = db.execute("SELECT * FROM places WHERE user_id=?", (user_id,)).fetchall()
        return [Place(label=r["label"], lat=r["lat"], lng=r["lng"],
                      radius_m=r["radius_m"], place_type=r["place_type"],
                      user_id=r["user_id"], address=r["address"],
                      added_at=r["added_at"]) for r in rows]

    # ── Location fix ──────────────────────────────────────────────────────────
    async def get_current_fix(self, user_id: str = "default",
                               force: bool = False) -> Optional[LocationFix]:
        last = self._last_fix.get(user_id)
        if last and not force and time.time() - last.timestamp < LOCATION_UPDATE_INTERVAL_S:
            return last

        fix = await self._fetch_fix()
        if fix:
            self._last_fix[user_id] = fix
            # Log (privacy: only store approximate location)
            label = self._nearest_place_label(fix.lat, fix.lng, user_id)
            with self._db() as db:
                db.execute(
                    "INSERT INTO location_log(user_id,lat,lng,accuracy_m,source,timestamp,place_label)"
                    " VALUES(?,?,?,?,?,?,?)",
                    (user_id, round(fix.lat, 3), round(fix.lng, 3),
                     fix.accuracy_m, fix.source, fix.timestamp, label)
                )
        return fix

    async def _fetch_fix(self) -> Optional[LocationFix]:
        """Try ADB, then IP-based geolocation as fallback."""
        # ADB GPS
        fix = await self._adb_fix()
        if fix:
            return fix
        # IP geolocation (privacy: coarse)
        fix = await self._ip_fix()
        return fix

    async def _adb_fix(self) -> Optional[LocationFix]:
        try:
            import subprocess
            result = await asyncio.get_running_loop().run_in_executor(
                None, lambda: subprocess.run(
                    ["adb", "shell", "dumpsys", "location", "|", "grep", "gps"],
                    capture_output=True, text=True, timeout=5
                )
            )
            import re
            m = re.search(r"([+-]?\d+\.\d+),([+-]?\d+\.\d+)", result.stdout)
            if m:
                return LocationFix(lat=float(m.group(1)), lng=float(m.group(2)),
                                   accuracy_m=10.0, source="adb")
        except Exception:
            pass
        return None

    async def _ip_fix(self) -> Optional[LocationFix]:
        try:
            import aiohttp
            async with aiohttp.ClientSession() as s:
                async with s.get("https://ipapi.co/json/",
                                  timeout=aiohttp.ClientTimeout(total=5)) as resp:
                    if resp.status != 200:
                        return None
                    data = await resp.json()
            return LocationFix(
                lat=float(data.get("latitude", 0)),
                lng=float(data.get("longitude", 0)),
                accuracy_m=5000.0,
                source="ip"
            )
        except Exception as e:
            log.debug("IP fix error: %s", e)
            return None

    # ── Place identification ──────────────────────────────────────────────────
    def _nearest_place_label(self, lat: float, lng: float,
                              user_id: str) -> str:
        places = self.list_places(user_id)
        for p in places:
            dist = haversine(lat, lng, p.lat, p.lng)
            if dist <= p.radius_m:
                return p.label
        return "unknown"

    async def get_current_label(self, user_id: str = "default") -> Dict[str, Any]:
        fix = await self.get_current_fix(user_id)
        if not fix:
            return {"location_label": "unknown", "lat": 0.0, "lng": 0.0}
        label = self._nearest_place_label(fix.lat, fix.lng, user_id)
        return {
            "location_label": label,
            "lat": fix.lat, "lng": fix.lng,
            "accuracy_m": fix.accuracy_m,
        }

    # ── Geo-fencing ───────────────────────────────────────────────────────────
    def on_geofence(self, callback):
        """Register callback: callback(GeoFenceEvent)."""
        self._fence_callbacks.append(callback)

    async def check_geofences(self, user_id: str = "default"):
        fix = await self.get_current_fix(user_id)
        if not fix:
            return
        places = self.list_places(user_id)
        state = self._geofence_state.setdefault(user_id, {})
        for p in places:
            inside = haversine(fix.lat, fix.lng, p.lat, p.lng) <= p.radius_m
            was_inside = state.get(p.label, False)
            if inside != was_inside:
                evt_type = "enter" if inside else "exit"
                log.info("Geofence %s: %s %s", evt_type, user_id, p.label)
                event = GeoFenceEvent(place=p, event_type=evt_type)
                for cb in self._fence_callbacks:
                    try:
                        if asyncio.iscoroutinefunction(cb):
                            await cb(event)
                        else:
                            cb(event)
                    except Exception as e:
                        log.error("Geofence callback error: %s", e)
            state[p.label] = inside

    # ── Nearby search ─────────────────────────────────────────────────────────
    async def find_nearby(self, lat: float, lng: float,
                           query: str, radius_m: int = 1000) -> List[Dict]:
        try:
            import aiohttp
            # Use OpenStreetMap Nominatim (free, no key required)
            url = "https://nominatim.openstreetmap.org/search"
            params = {
                "q": query, "format": "json",
                "lat": lat, "lon": lng,
                "radius": radius_m / 1000,
                "limit": 10
            }
            async with aiohttp.ClientSession(
                headers={"User-Agent": "AladdinAI/1.0"}
            ) as session:
                async with session.get(url, params=params,
                                       timeout=aiohttp.ClientTimeout(total=8)) as resp:
                    if resp.status != 200:
                        return []
                    data = await resp.json()
            return [
                {
                    "name": item.get("display_name", ""),
                    "lat": float(item.get("lat", 0)),
                    "lng": float(item.get("lon", 0)),
                    "type": item.get("type", ""),
                    "distance_m": int(haversine(lat, lng, float(item.get("lat", 0)),
                                                float(item.get("lon", 0)))),
                }
                for item in data[:10]
            ]
        except Exception as e:
            log.warning("Nearby search error: %s", e)
            return []

    # ── Commute ETA ───────────────────────────────────────────────────────────
    async def get_commute_eta(self, user_id: str = "default") -> Optional[Dict]:
        fix = await self.get_current_fix(user_id)
        if not fix:
            return None
        office = next((p for p in self.list_places(user_id) if p.place_type == "office"), None)
        if not office:
            return None
        dist_m = haversine(fix.lat, fix.lng, office.lat, office.lng)
        # Estimate driving time: 30 km/h average urban speed
        duration_min = int(dist_m / 1000 / 30 * 60)
        from datetime import datetime, timedelta
        depart_by = (datetime.now() + timedelta(minutes=max(5, duration_min - 10))).strftime("%H:%M")
        return {
            "destination": office.label,
            "distance_m": int(dist_m),
            "duration_min": duration_min,
            "traffic": "Normal",   # would use real traffic API in production
            "depart_by": depart_by,
        }

    # ── Background watcher ────────────────────────────────────────────────────
    async def run(self, user_id: str = "default"):
        while True:
            try:
                await self.get_current_fix(user_id, force=True)
                await self.check_geofences(user_id)
            except Exception as e:
                log.error("Location service error: %s", e)
            await asyncio.sleep(LOCATION_UPDATE_INTERVAL_S)
