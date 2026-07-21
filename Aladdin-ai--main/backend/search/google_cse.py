"""Google Custom Search Engine (CSE) integration."""
from __future__ import annotations
import logging
import os
import time
from typing import Any, Dict, List, Optional

import aiohttp

log = logging.getLogger(__name__)
CSE_BASE = "https://www.googleapis.com/customsearch/v1"


class GoogleCSE:
    def __init__(self, api_key: Optional[str] = None, cx: Optional[str] = None) -> None:
        self._key = api_key or os.getenv("GOOGLE_CSE_API_KEY", "")
        self._cx  = cx      or os.getenv("GOOGLE_CSE_CX", "")

    @property
    def available(self) -> bool:
        return bool(self._key and self._cx)

    async def search(self, query: str, num: int = 10, start: int = 1,
                     search_type: Optional[str] = None) -> Dict[str, Any]:
        if not self.available:
            return {"error": "GOOGLE_CSE_API_KEY and GOOGLE_CSE_CX not set", "results": []}
        t0 = time.time()
        params: Dict[str, Any] = {"key": self._key, "cx": self._cx,
                                   "q": query, "num": min(num, 10), "start": start}
        if search_type:
            params["searchType"] = search_type  # "image"
        async with aiohttp.ClientSession() as s:
            r = await s.get(CSE_BASE, params=params, timeout=aiohttp.ClientTimeout(total=15))
            data = await r.json()
        if "error" in data:
            return {"error": data["error"].get("message",""), "results": []}
        results = []
        for item in data.get("items", []):
            results.append({"title": item.get("title",""), "url": item.get("link",""),
                            "snippet": item.get("snippet",""),
                            "display_url": item.get("displayLink",""),
                            "image": item.get("pagemap",{}).get("cse_image",[{}])[0].get("src",""),
                            "source": "google_cse"})
        return {"query": query, "results": results,
                "total": data.get("searchInformation",{}).get("totalResults",""),
                "duration_ms": (time.time()-t0)*1000}

    async def image_search(self, query: str, num: int = 10) -> Dict[str, Any]:
        return await self.search(query, num, search_type="image")
