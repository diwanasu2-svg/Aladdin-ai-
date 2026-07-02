"""News API integration + fallback RSS scrapers."""
from __future__ import annotations
import logging
import os
import time
from typing import Any, Dict, List, Optional

import aiohttp

log = logging.getLogger(__name__)
NEWSAPI_BASE = "https://newsapi.org/v2"


class NewsSearch:
    def __init__(self, api_key: Optional[str] = None) -> None:
        self._key = api_key or os.getenv("NEWSAPI_KEY", "")

    @property
    def available(self) -> bool:
        return bool(self._key)

    async def top_headlines(self, query: Optional[str] = None, category: str = "general",
                            country: str = "us", page_size: int = 10) -> Dict[str, Any]:
        params: Dict[str, Any] = {"apiKey": self._key, "category": category,
                                   "country": country, "pageSize": page_size}
        if query:
            params["q"] = query
        return await self._call("top-headlines", params)

    async def everything(self, query: str, sort_by: str = "publishedAt",
                         language: str = "en", page_size: int = 10,
                         from_date: Optional[str] = None) -> Dict[str, Any]:
        params: Dict[str, Any] = {"apiKey": self._key, "q": query,
                                   "sortBy": sort_by, "language": language,
                                   "pageSize": page_size}
        if from_date:
            params["from"] = from_date
        return await self._call("everything", params)

    async def sources(self, category: str = "general", language: str = "en") -> Dict[str, Any]:
        params = {"apiKey": self._key, "category": category, "language": language}
        return await self._call("sources", params)

    async def _call(self, endpoint: str, params: Dict) -> Dict[str, Any]:
        if not self.available:
            return {"error": "NEWSAPI_KEY not set. Get one free at newsapi.org", "articles": []}
        t0 = time.time()
        try:
            async with aiohttp.ClientSession() as s:
                r = await s.get(f"{NEWSAPI_BASE}/{endpoint}", params=params,
                                timeout=aiohttp.ClientTimeout(total=10))
                data = await r.json()
            articles = [{"title": a.get("title",""), "url": a.get("url",""),
                         "source": a.get("source",{}).get("name",""),
                         "published_at": a.get("publishedAt",""),
                         "description": a.get("description",""),
                         "image_url": a.get("urlToImage","")}
                        for a in data.get("articles", [])]
            return {"status": data.get("status"), "total": data.get("totalResults",0),
                    "articles": articles, "duration_ms": (time.time()-t0)*1000}
        except Exception as exc:
            return {"error": str(exc), "articles": []}


async def rss_news(feed_url: str, max_items: int = 10) -> List[Dict]:
    """Fallback: scrape an RSS feed without an API key."""
    import re
    try:
        async with aiohttp.ClientSession() as s:
            r = await s.get(feed_url, timeout=aiohttp.ClientTimeout(total=10))
            xml = await r.text()
        items = re.findall(r"<item>(.*?)</item>", xml, re.DOTALL)
        results = []
        for item in items[:max_items]:
            def _tag(name):
                m = re.search(rf"<{name}[^>]*>(.*?)</{name}>", item, re.DOTALL)
                return m.group(1).strip() if m else ""
            results.append({"title": _tag("title"), "url": _tag("link"),
                            "description": _tag("description"), "published_at": _tag("pubDate")})
        return results
    except Exception as exc:
        return [{"error": str(exc)}]
