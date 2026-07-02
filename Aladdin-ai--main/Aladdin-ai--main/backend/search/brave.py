"""Brave Search API integration."""
from __future__ import annotations
import logging
import os
import time
from typing import Any, Dict, List, Optional

import aiohttp

log = logging.getLogger(__name__)
BRAVE_BASE = "https://api.search.brave.com/res/v1"


class BraveSearch:
    def __init__(self, api_key: Optional[str] = None) -> None:
        self._key = api_key or os.getenv("BRAVE_SEARCH_API_KEY", "")

    @property
    def available(self) -> bool:
        return bool(self._key)

    def _headers(self):
        return {"Accept": "application/json",
                "Accept-Encoding": "gzip",
                "X-Subscription-Token": self._key}

    async def web(self, query: str, count: int = 10, country: str = "US",
                  search_lang: str = "en") -> Dict[str, Any]:
        if not self.available:
            return {"error": "BRAVE_SEARCH_API_KEY not set", "results": []}
        t0 = time.time()
        params = {"q": query, "count": count, "country": country, "search_lang": search_lang}
        async with aiohttp.ClientSession(headers=self._headers()) as s:
            r = await s.get(f"{BRAVE_BASE}/web/search", params=params,
                            timeout=aiohttp.ClientTimeout(total=15))
            data = await r.json()
        results = []
        for item in data.get("web", {}).get("results", []):
            results.append({"title": item.get("title",""), "url": item.get("url",""),
                            "snippet": item.get("description",""),
                            "age": item.get("age",""), "source": "brave_web"})
        return {"query": query, "results": results, "duration_ms": (time.time()-t0)*1000}

    async def news(self, query: str, count: int = 10) -> Dict[str, Any]:
        if not self.available:
            return {"error": "BRAVE_SEARCH_API_KEY not set", "results": []}
        params = {"q": query, "count": count}
        async with aiohttp.ClientSession(headers=self._headers()) as s:
            r = await s.get(f"{BRAVE_BASE}/news/search", params=params,
                            timeout=aiohttp.ClientTimeout(total=15))
            data = await r.json()
        results = [{"title": i.get("title",""), "url": i.get("url",""),
                    "snippet": i.get("description",""), "age": i.get("age",""),
                    "source": "brave_news"} for i in data.get("results", [])]
        return {"query": query, "results": results}

    async def images(self, query: str, count: int = 10) -> Dict[str, Any]:
        if not self.available:
            return {"error": "BRAVE_SEARCH_API_KEY not set", "results": []}
        params = {"q": query, "count": count}
        async with aiohttp.ClientSession(headers=self._headers()) as s:
            r = await s.get(f"{BRAVE_BASE}/images/search", params=params,
                            timeout=aiohttp.ClientTimeout(total=15))
            data = await r.json()
        results = [{"title": i.get("title",""), "url": i.get("url",""),
                    "image_url": i.get("properties",{}).get("url",""),
                    "source": "brave_images"} for i in data.get("results", [])]
        return {"query": query, "results": results}
