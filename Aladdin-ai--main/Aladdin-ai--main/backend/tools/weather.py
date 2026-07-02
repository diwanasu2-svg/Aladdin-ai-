"""Weather tool — current + 5-day forecast via Open-Meteo (free, no key)."""
from __future__ import annotations
import logging, time
from typing import Optional
import aiohttp
from .base import BaseTool, ToolResult

log = logging.getLogger(__name__)

GEO_URL = "https://geocoding-api.open-meteo.com/v1/search"
WX_URL  = "https://api.open-meteo.com/v1/forecast"
WMO_CODES = {0:"Clear sky",1:"Mainly clear",2:"Partly cloudy",3:"Overcast",
             45:"Fog",48:"Icing fog",51:"Light drizzle",53:"Moderate drizzle",
             55:"Dense drizzle",61:"Slight rain",63:"Moderate rain",65:"Heavy rain",
             71:"Slight snow",73:"Moderate snow",75:"Heavy snow",80:"Rain showers",
             81:"Moderate showers",82:"Violent showers",95:"Thunderstorm",99:"Thunderstorm with hail"}


async def _geocode(location: str):
    async with aiohttp.ClientSession() as s:
        r = await s.get(GEO_URL, params={"name": location, "count": 1, "language": "en", "format": "json"})
        d = await r.json()
    res = d.get("results", [])
    if not res:
        raise ValueError(f"Location not found: {location}")
    return res[0]["latitude"], res[0]["longitude"], res[0].get("name", location)


class WeatherTool(BaseTool):
    name = "get_weather"
    description = "Get current weather and 5-day forecast for any location."
    parameters = {"type": "object", "properties": {
        "location": {"type": "string", "description": "City name or address"},
        "forecast_days": {"type": "integer", "description": "Days of forecast (1-7)", "default": 5}},
        "required": ["location"]}

    async def execute(self, location: str, forecast_days: int = 5) -> ToolResult:
        t0 = time.time()
        try:
            lat, lon, name = await _geocode(location)
            days = min(max(forecast_days, 1), 7)
            params = {"latitude": lat, "longitude": lon, "current_weather": True,
                      "daily": "temperature_2m_max,temperature_2m_min,weathercode,precipitation_sum",
                      "timezone": "auto", "forecast_days": days}
            async with aiohttp.ClientSession() as s:
                r = await s.get(WX_URL, params=params)
                wx = await r.json()
            cur = wx.get("current_weather", {})
            daily = wx.get("daily", {})
            forecast = []
            for i, date in enumerate(daily.get("time", [])):
                code = daily.get("weathercode", [None]*8)[i]
                forecast.append({
                    "date": date,
                    "condition": WMO_CODES.get(code, "Unknown"),
                    "temp_max": daily.get("temperature_2m_max", [None]*8)[i],
                    "temp_min": daily.get("temperature_2m_min", [None]*8)[i],
                    "precipitation_mm": daily.get("precipitation_sum", [None]*8)[i],
                })
            data = {"location": name, "latitude": lat, "longitude": lon,
                    "current": {"temp_c": cur.get("temperature"), "wind_kph": cur.get("windspeed"),
                                "condition": WMO_CODES.get(cur.get("weathercode", 0), "Unknown")},
                    "forecast": forecast}
            return ToolResult(True, self.name, data, duration_ms=(time.time()-t0)*1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time()-t0)*1000)
