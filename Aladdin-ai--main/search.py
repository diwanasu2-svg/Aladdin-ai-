"""
Phase 6 — Search Verification & Enhanced Search
================================================
- DuckDuckGo (no key, HTML + Instant Answer)
- Brave Search API
- Google Custom Search
- Wikipedia REST API
- News search (NewsAPI + DDG news)
- Automatic search triggering heuristics
- Result ranking by relevance
- Source citation
- LRU search cache (disk + memory)
- Offline fallback
- Per-provider timeout handling
"""

from __future__ import annotations

import hashlib
import json
import logging
import re
import time
from dataclasses import dataclass, asdict
from functools import lru_cache
from pathlib import Path
from typing import List, Optional
from urllib.parse import quote_plus

import requests

log = logging.getLogger(__name__)


# ── Simple config stub (used when SearchCfg not available) ──────────────────
class _DefaultSearchCfg:
    enabled = True
    provider = "duckduckgo"
    api_key = ""
    max_results = 5
    timeout = 8
    cache_ttl = 600  # seconds
    news_api_key = ""
    cache_dir = "data/search_cache"


try:
    from aladdin_core.config import SearchCfg as _SearchCfg  # type: ignore

    _CfgBase = _SearchCfg
except ImportError:
    _CfgBase = _DefaultSearchCfg


# ── Search result ────────────────────────────────────────────────────────────


@dataclass
class SearchResult:
    title: str
    url: str
    snippet: str
    source: str = "web"
    score: float = 0.0

    def to_dict(self):
        return asdict(self)

    def citation(self) -> str:
        return f"[{self.title}]({self.url})" if self.url else self.title


# ── Disk cache ────────────────────────────────────────────────────────────────


class _DiskCache:
    def __init__(self, cache_dir: str, ttl: int):
        self._dir = Path(cache_dir)
        self._dir.mkdir(parents=True, exist_ok=True)
        self._ttl = ttl

    def _key(self, query: str) -> Path:
        h = hashlib.md5(query.lower().encode()).hexdigest()
        return self._dir / f"{h}.json"

    def get(self, query: str) -> Optional[List[SearchResult]]:
        p = self._key(query)
        if not p.exists():
            return None
        if time.time() - p.stat().st_mtime > self._ttl:
            p.unlink(missing_ok=True)
            return None
        try:
            data = json.loads(p.read_text())
            return [SearchResult(**d) for d in data]
        except Exception:
            return None

    def set(self, query: str, results: List[SearchResult]) -> None:
        try:
            p = self._key(query)
            p.write_text(json.dumps([r.to_dict() for r in results]))
        except Exception as exc:
            log.debug("Cache write error: %s", exc)


# ── Trigger heuristics ────────────────────────────────────────────────────────

_SEARCH_TRIGGERS = re.compile(
    r"\b(search|look up|find|what is|who is|when did|where is|"
    r"latest|news|weather|stock|price|define|wikipedia|how to|"
    r"tell me about|show me|results for)\b",
    re.IGNORECASE,
)


def needs_search(text: str) -> bool:
    """Return True if the query likely benefits from a web search."""
    return bool(_SEARCH_TRIGGERS.search(text))


# ── Ranking ───────────────────────────────────────────────────────────────────


def _rank(results: List[SearchResult], query: str) -> List[SearchResult]:
    """Simple TF-style relevance ranking."""
    q_words = set(query.lower().split())
    for r in results:
        blob = (r.title + " " + r.snippet).lower()
        hits = sum(blob.count(w) for w in q_words)
        r.score = hits / (len(blob.split()) + 1)
    return sorted(results, key=lambda r: -r.score)


# ── Main class ────────────────────────────────────────────────────────────────


class InternetSearch:
    """
    Unified internet search interface with caching, ranking, and fallback.
    """

    def __init__(self, cfg=None):
        if cfg is None:
            cfg = _DefaultSearchCfg()
        self.cfg = cfg
        self._cache = _DiskCache(
            getattr(cfg, "cache_dir", "data/search_cache"),
            getattr(cfg, "cache_ttl", 600),
        )
        self._session = requests.Session()
        self._session.headers["User-Agent"] = "Aladdin-Assistant/2.0"

    # ── Public API ────────────────────────────────────────────────────────

    def search(self, query: str, provider: Optional[str] = None) -> List[SearchResult]:
        if not getattr(self.cfg, "enabled", True):
            return []

        # Cache hit
        cached = self._cache.get(query)
        if cached:
            log.debug("Search cache hit for: %s", query[:50])
            return cached

        provider = provider or getattr(self.cfg, "provider", "duckduckgo")
        try:
            if provider == "brave" and getattr(self.cfg, "api_key", ""):
                results = self._brave(query)
            elif provider == "google" and getattr(self.cfg, "api_key", ""):
                results = self._google(query)
            elif provider == "wikipedia":
                results = self._wikipedia(query)
            elif provider == "news":
                results = self._news(query)
            else:
                results = self._duckduckgo(query)
        except Exception as exc:
            log.warning("Search provider '%s' failed: %s", provider, exc)
            results = self._offline_fallback(query)

        if results:
            results = _rank(results, query)
            self._cache.set(query, results)

        return results[: getattr(self.cfg, "max_results", 5)]

    def answer(self, query: str) -> Optional[str]:
        """Return a concise, voice-friendly answer string."""
        results = self.search(query)
        if not results:
            return None
        snippets = [r.snippet for r in results if r.snippet][:3]
        if not snippets:
            return None
        combined = " ".join(snippets)
        return (
            (combined[:300].rsplit(" ", 1)[0] + ".")
            if len(combined) > 300
            else combined
        )

    def citations(self, query: str) -> List[str]:
        """Return citation strings for a query."""
        return [r.citation() for r in self.search(query) if r.url]

    # ── Providers ─────────────────────────────────────────────────────────

    def _duckduckgo(self, query: str) -> List[SearchResult]:
        """DuckDuckGo Instant Answer API then HTML scrape fallback."""
        timeout = getattr(self.cfg, "timeout", 8)
        url = "https://api.duckduckgo.com/"
        params = {"q": query, "format": "json", "no_html": "1", "skip_disambig": "1"}
        try:
            r = self._session.get(url, params=params, timeout=timeout)
            r.raise_for_status()
            data = r.json()
        except Exception as exc:
            log.debug("DDG instant API failed: %s", exc)
            return self._ddg_html(query)

        results: List[SearchResult] = []
        abstract = data.get("AbstractText", "")
        if abstract:
            results.append(
                SearchResult(
                    title=data.get("Heading", query),
                    url=data.get("AbstractURL", ""),
                    snippet=abstract,
                    source="duckduckgo",
                )
            )
        for topic in data.get("RelatedTopics", [])[:8]:
            if isinstance(topic, dict) and "Text" in topic:
                results.append(
                    SearchResult(
                        title=topic["Text"][:80],
                        url=topic.get("FirstURL", ""),
                        snippet=topic["Text"],
                        source="duckduckgo",
                    )
                )
        if not results:
            results = self._ddg_html(query)
        return results

    def _ddg_html(self, query: str) -> List[SearchResult]:
        timeout = getattr(self.cfg, "timeout", 8)
        url = f"https://html.duckduckgo.com/html/?q={quote_plus(query)}"
        try:
            r = self._session.get(url, timeout=timeout)
            r.raise_for_status()
            snippets = re.findall(
                r'class="result__snippet"[^>]*>([^<]{20,300})', r.text
            )
            titles = re.findall(
                r'class="result__title"[^>]*>.*?<a[^>]*>([^<]{5,100})', r.text
            )
            urls = re.findall(r'class="result__url"[^>]*>([^<]{5,200})', r.text)
            out = []
            for i, snip in enumerate(snippets):
                clean = re.sub(r"<[^>]+>", "", snip).strip()
                if clean:
                    out.append(
                        SearchResult(
                            title=titles[i] if i < len(titles) else query,
                            url=urls[i].strip() if i < len(urls) else "",
                            snippet=clean,
                            source="duckduckgo",
                        )
                    )
            return out
        except Exception as exc:
            log.debug("DDG HTML fallback failed: %s", exc)
            return []

    def _brave(self, query: str) -> List[SearchResult]:
        timeout = getattr(self.cfg, "timeout", 8)
        headers = {
            "Accept": "application/json",
            "X-Subscription-Token": self.cfg.api_key,
        }
        params = {"q": query, "count": getattr(self.cfg, "max_results", 5)}
        r = self._session.get(
            "https://api.search.brave.com/res/v1/web/search",
            headers=headers,
            params=params,
            timeout=timeout,
        )
        r.raise_for_status()
        data = r.json()
        results = []
        for item in data.get("web", {}).get("results", []):
            results.append(
                SearchResult(
                    title=item.get("title", ""),
                    url=item.get("url", ""),
                    snippet=item.get("description", ""),
                    source="brave",
                )
            )
        return results

    def _google(self, query: str) -> List[SearchResult]:
        timeout = getattr(self.cfg, "timeout", 8)
        params = {
            "key": self.cfg.api_key,
            "cx": getattr(self.cfg, "google_cx", ""),
            "q": query,
            "num": getattr(self.cfg, "max_results", 5),
        }
        r = self._session.get(
            "https://www.googleapis.com/customsearch/v1",
            params=params,
            timeout=timeout,
        )
        r.raise_for_status()
        return [
            SearchResult(
                title=item.get("title", ""),
                url=item.get("link", ""),
                snippet=item.get("snippet", ""),
                source="google",
            )
            for item in r.json().get("items", [])
        ]

    def _wikipedia(self, query: str) -> List[SearchResult]:
        """Wikipedia REST API search."""
        timeout = getattr(self.cfg, "timeout", 8)
        search_url = "https://en.wikipedia.org/w/api.php"
        params = {
            "action": "query",
            "list": "search",
            "srsearch": query,
            "format": "json",
            "srlimit": 3,
        }
        try:
            r = self._session.get(search_url, params=params, timeout=timeout)
            r.raise_for_status()
            items = r.json().get("query", {}).get("search", [])
            results = []
            for item in items:
                title = item.get("title", "")
                snippet = re.sub(r"<[^>]+>", "", item.get("snippet", ""))
                slug = title.replace(" ", "_")
                results.append(
                    SearchResult(
                        title=title,
                        url=f"https://en.wikipedia.org/wiki/{slug}",
                        snippet=snippet,
                        source="wikipedia",
                    )
                )
            return results
        except Exception as exc:
            log.debug("Wikipedia search failed: %s", exc)
            return []

    def _news(self, query: str) -> List[SearchResult]:
        """News search: NewsAPI if key available, else DDG news."""
        timeout = getattr(self.cfg, "timeout", 8)
        news_key = getattr(self.cfg, "news_api_key", "")
        if news_key:
            try:
                params = {
                    "q": query,
                    "apiKey": news_key,
                    "pageSize": getattr(self.cfg, "max_results", 5),
                    "sortBy": "relevancy",
                }
                r = self._session.get(
                    "https://newsapi.org/v2/everything",
                    params=params,
                    timeout=timeout,
                )
                r.raise_for_status()
                articles = r.json().get("articles", [])
                return [
                    SearchResult(
                        title=a.get("title", ""),
                        url=a.get("url", ""),
                        snippet=a.get("description") or a.get("content") or "",
                        source="newsapi",
                    )
                    for a in articles
                ]
            except Exception as exc:
                log.debug("NewsAPI failed: %s", exc)

        # Fallback: DuckDuckGo news tab
        return self._ddg_html(f"{query} news")

    def _offline_fallback(self, query: str) -> List[SearchResult]:
        """Return empty list with a note; caller must handle gracefully."""
        log.info("Search offline fallback for: %s", query[:60])
        return []
