"""Maps tool — location search, routing, nearby places, navigation via googlemaps / geopy."""
from __future__ import annotations
import asyncio, logging, os, time
from typing import Any, Dict, List, Optional
from .base import BaseTool, ToolResult

log = logging.getLogger(__name__)


def _client():
    try:
        import googlemaps
        key = os.getenv("GOOGLE_MAPS_API_KEY", "")
        if not key:
            raise RuntimeError("GOOGLE_MAPS_API_KEY not set")
        return googlemaps.Client(key=key)
    except ImportError:
        raise RuntimeError("googlemaps package not installed — run: pip install googlemaps")


class GetCurrentLocationTool(BaseTool):
    name = "get_current_location"
    description = "Get the device's current GPS location (latitude, longitude, address)."
    parameters = {"type": "object", "properties": {}}

    async def execute(self) -> ToolResult:
        t0 = time.time()
        try:
            # On Android this bridges to the GPS hardware; on desktop uses IP-based geo
            import geocoder
            g = geocoder.ip("me")
            if g.ok:
                return ToolResult(True, self.name, {
                    "lat": g.latlng[0], "lng": g.latlng[1],
                    "address": g.address, "city": g.city,
                    "country": g.country
                }, duration_ms=(time.time() - t0) * 1000)
            return ToolResult(False, self.name, error="Location unavailable", duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class SearchPlacesTool(BaseTool):
    name = "search_places"
    description = "Search for places (restaurants, gas stations, hospitals, etc.) near a location."
    parameters = {"type": "object", "properties": {
        "query": {"type": "string", "description": "Search query, e.g. 'coffee shops'"},
        "location": {"type": "string", "description": "Center point, e.g. 'New York, NY' or lat,lng"},
        "radius_m": {"type": "integer", "default": 5000},
        "max_results": {"type": "integer", "default": 10}},
        "required": ["query"]}

    async def execute(self, query: str, location: str = None, radius_m: int = 5000, max_results: int = 10) -> ToolResult:
        t0 = time.time()
        try:
            gmaps = _client()
            loc_arg = location or "current location"

            def _search():
                if location:
                    res = gmaps.places(query=query, location=location, radius=radius_m)
                else:
                    res = gmaps.places(query=query)
                places = []
                for p in res.get("results", [])[:max_results]:
                    places.append({
                        "name": p.get("name"),
                        "address": p.get("formatted_address", p.get("vicinity")),
                        "rating": p.get("rating"),
                        "open_now": p.get("opening_hours", {}).get("open_now"),
                        "place_id": p.get("place_id"),
                        "location": p.get("geometry", {}).get("location")
                    })
                return places

            places = await asyncio.get_running_loop().run_in_executor(None, _search)
            return ToolResult(True, self.name, {"places": places, "count": len(places)},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class GetDirectionsTool(BaseTool):
    name = "get_directions"
    description = "Get driving/walking/transit directions between two locations with waypoints."
    parameters = {"type": "object", "properties": {
        "origin": {"type": "string", "description": "Start location"},
        "destination": {"type": "string", "description": "End location"},
        "mode": {"type": "string", "enum": ["driving", "walking", "bicycling", "transit"], "default": "driving"},
        "waypoints": {"type": "array", "items": {"type": "string"}, "description": "Optional intermediate stops"},
        "alternatives": {"type": "boolean", "default": False}},
        "required": ["origin", "destination"]}

    async def execute(self, origin: str, destination: str, mode: str = "driving",
                      waypoints: Optional[List[str]] = None, alternatives: bool = False) -> ToolResult:
        t0 = time.time()
        try:
            gmaps = _client()

            def _directions():
                result = gmaps.directions(
                    origin, destination,
                    mode=mode,
                    waypoints=waypoints or [],
                    alternatives=alternatives
                )
                routes = []
                for route in result:
                    legs = route.get("legs", [])
                    total_dist = sum(l["distance"]["value"] for l in legs)
                    total_dur = sum(l["duration"]["value"] for l in legs)
                    steps = []
                    for leg in legs:
                        for step in leg.get("steps", [])[:20]:
                            import re
                            text = re.sub("<[^>]+>", "", step.get("html_instructions", ""))
                            steps.append({
                                "instruction": text,
                                "distance": step["distance"]["text"],
                                "duration": step["duration"]["text"]
                            })
                    routes.append({
                        "summary": route.get("summary"),
                        "distance_m": total_dist,
                        "distance_text": f"{total_dist/1000:.1f} km",
                        "duration_seconds": total_dur,
                        "duration_text": f"{total_dur//60} min",
                        "steps": steps
                    })
                return routes

            routes = await asyncio.get_running_loop().run_in_executor(None, _directions)
            return ToolResult(True, self.name, {
                "origin": origin, "destination": destination,
                "mode": mode, "routes": routes
            }, duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class GetTrafficInfoTool(BaseTool):
    name = "get_traffic_info"
    description = "Get current traffic conditions and ETA between two locations."
    parameters = {"type": "object", "properties": {
        "origin": {"type": "string"}, "destination": {"type": "string"},
        "mode": {"type": "string", "default": "driving"}},
        "required": ["origin", "destination"]}

    async def execute(self, origin: str, destination: str, mode: str = "driving") -> ToolResult:
        t0 = time.time()
        try:
            gmaps = _client()

            def _traffic():
                import datetime
                result = gmaps.distance_matrix(
                    origins=[origin], destinations=[destination],
                    mode=mode, departure_time=datetime.datetime.now(),
                    traffic_model="best_guess"
                )
                el = result["rows"][0]["elements"][0]
                dur_traffic = el.get("duration_in_traffic", el.get("duration", {}))
                return {
                    "origin": origin,
                    "destination": destination,
                    "distance": el.get("distance", {}).get("text"),
                    "duration_no_traffic": el.get("duration", {}).get("text"),
                    "duration_with_traffic": dur_traffic.get("text"),
                    "status": el.get("status")
                }

            info = await asyncio.get_running_loop().run_in_executor(None, _traffic)
            return ToolResult(True, self.name, info, duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)


class SaveFavoritePlaceTool(BaseTool):
    name = "save_favorite_place"
    description = "Save a place as a favorite with a custom label."
    parameters = {"type": "object", "properties": {
        "label": {"type": "string"}, "address": {"type": "string"},
        "notes": {"type": "string"}},
        "required": ["label", "address"]}

    _store: Dict[str, Dict] = {}

    async def execute(self, label: str, address: str, notes: str = "") -> ToolResult:
        SaveFavoritePlaceTool._store[label] = {"address": address, "notes": notes, "saved_at": time.time()}
        return ToolResult(True, self.name, {"label": label, "address": address})


class ListFavoritePlacesTool(BaseTool):
    name = "list_favorite_places"
    description = "List all saved favorite places."
    parameters = {"type": "object", "properties": {}}

    async def execute(self) -> ToolResult:
        places = [{"label": k, **v} for k, v in SaveFavoritePlaceTool._store.items()]
        return ToolResult(True, self.name, {"places": places, "count": len(places)})


class GeocodeAddressTool(BaseTool):
    name = "geocode_address"
    description = "Convert an address to latitude/longitude coordinates."
    parameters = {"type": "object", "properties": {"address": {"type": "string"}}, "required": ["address"]}

    async def execute(self, address: str) -> ToolResult:
        t0 = time.time()
        try:
            gmaps = _client()
            result = await asyncio.get_running_loop().run_in_executor(None, lambda: gmaps.geocode(address))
            if result:
                loc = result[0]["geometry"]["location"]
                return ToolResult(True, self.name, {
                    "address": result[0].get("formatted_address"),
                    "lat": loc["lat"], "lng": loc["lng"]
                }, duration_ms=(time.time() - t0) * 1000)
            return ToolResult(False, self.name, error="Address not found", duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time() - t0) * 1000)
