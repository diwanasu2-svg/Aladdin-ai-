"""Unified SearchEngine — routes queries across DuckDuckGo, Brave, Google CSE, News."""
from __future__ import annotations
import asyncio
import json
import logging
import time
import uuid
from typing import Any, Dict, List, Optional

import aiohttp

from .scraper import scrape
from .citations import build_citations
from .brave import BraveSearch
from .google_cse import GoogleCSE
from .news import NewsSearch

log = logging.getLogger(__name__)
_HISTORY: List[Dict] = []
_RESULTS_CACHE: Dict[str, Dict] = {}  # result_id -> result


class SearchEngine:
    def __init__(self) -> None:
        self.brave = BraveSearch()
        self.google = GoogleCSE()
        self.news = NewsSearch()

    async def search(self, query: str, engine: str = "auto", max_results: int = 10,
                     include_citations: bool = True, scrape_top: int = 0) -> Dict[str, Any]:
        t0 = time.time()
        results: List[Dict] = []

        if engine in ("brave", "auto") and self.brave.available:
            data = await self.brave.web(query, count=max_results)
            results = data.get("results", [])
        elif engine in ("google", "auto") and self.google.available:
            data = await self.google.search(query, num=max_results)
            results = data.get("results", [])
        else:
            results = await self._ddg_search(query, max_results)

        # Optionally scrape top N results for full text
        if scrape_top > 0:
            top = results[:scrape_top]
            scraped = await asyncio.gather(*[scrape(r["url"], max_chars=2000) for r in top],
                                           return_exceptions=True)
            for i, s in enumerate(scraped):
                if isinstance(s, dict) and not s.get("error"):
                    results[i]["full_text"] = s.get("text", "")

        citations = build_citations(results) if include_citations else []

        # Cache each result
        result_id = str(uuid.uuid4())
        _RESULTS_CACHE[result_id] = {"query": query, "results": results, "citations": citations}

        # Add to history
        _HISTORY.append({"result_id": result_id, "query": query, "engine": engine,
                         "count": len(results), "ts": time.time()})
        if len(_HISTORY) > 500:
            _HISTORY.pop(0)

        return {"result_id": result_id, "query": query, "engine": engine,
                "results": results, "citations": citations,
                "duration_ms": (time.time()-t0)*1000}

    async def stream_search(self, query: str, engine: str = "auto", max_results: int = 10):
        """Async generator yielding SSE-formatted search result chunks."""
        t0 = time.time()
        yield f"data: {json.dumps({'type':'start','query':query})}\n\n"

        if engine in ("brave", "auto") and self.brave.available:
            data = await self.brave.web(query, count=max_results)
            results = data.get("results", [])
        elif engine in ("google", "auto") and self.google.available:
            data = await self.google.search(query, num=max_results)
            results = data.get("results", [])
        else:
            results = await self._ddg_search(query, max_results)

        citations = build_citations(results)

        for i, r in enumerate(results):
            yield f"data: {json.dumps({'type':'result','index':i+1,'data':r})}\n\n"
            await asyncio.sleep(0.05)

        yield f"data: {json.dumps({'type':'citations','data':citations})}\n\n"
        yield f"data: {json.dumps({'type':'done','duration_ms':(time.time()-t0)*1000,'total':len(results)})}\n\n"

    async def _ddg_search(self, query: str, count: int = 10) -> List[Dict]:
        """DuckDuckGo Instant Answers fallback (free, no key)."""
        results = []
        try:
            async with aiohttp.ClientSession() as s:
                r = await s.get("https://api.duckduckgo.com/", params={
                    "q": query, "format": "json", "no_html": 1, "skip_disambig": 1},
                    timeout=aiohttp.ClientTimeout(total=10))
                d = await r.json(content_type=None)
            if d.get("AbstractText"):
                results.append({"title": d.get("Heading",""), "url": d.get("AbstractURL",""),
                                 "snippet": d["AbstractText"], "source": "duckduckgo"})
            for rel in d.get("RelatedTopics", []):
                if isinstance(rel, dict) and rel.get("Text") and len(results) < count:
                    results.append({"title": rel.get("Text","")[:80], "url": rel.get("FirstURL",""),
                                    "snippet": rel.get("Text",""), "source": "duckduckgo"})
        except Exception as exc:
            log.warning("DuckDuckGo search failed: %s", exc)
        return results[:count]

    def get_result(self, result_id: str) -> Optional[Dict]:
        return _RESULTS_CACHE.get(result_id)

    def get_history(self, limit: int = 50) -> List[Dict]:
        return _HISTORY[-limit:]

    async def suggestions(self, partial: str) -> List[str]:
        """Autocomplete suggestions from DuckDuckGo."""
        try:
            async with aiohttp.ClientSession() as s:
                r = await s.get("https://duckduckgo.com/ac/",
                                params={"q": partial, "type": "list"},
                                timeout=aiohttp.ClientTimeout(total=5))
                data = await r.json()
            if isinstance(data, list) and len(data) > 1:
                return data[1][:10]
        except Exception:
            pass
        return []
