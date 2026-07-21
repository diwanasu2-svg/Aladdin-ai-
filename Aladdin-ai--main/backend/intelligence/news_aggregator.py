"""Phase 11.4 — News Aggregator.

Fetches news from multiple trusted sources, deduplicates, summarises,
filters by user interests, and categorises articles.

Primary source: NewsAPI (news_api_key required).
Fallback: RSS feeds parsed with feedparser.
"""
from __future__ import annotations
import asyncio
import hashlib
import json
import logging
import time
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Set
from difflib import SequenceMatcher

from .config import (
    NEWS_API_KEY, NEWS_SOURCES, NEWS_MAX_ARTICLES,
    NEWS_SUMMARY_MAX_WORDS, NEWS_CACHE_TTL_S,
)

log = logging.getLogger(__name__)

CATEGORIES = ["technology", "business", "science", "health", "sports",
              "entertainment", "politics", "world", "environment"]

RSS_FALLBACK_FEEDS = {
    "bbc": "http://feeds.bbci.co.uk/news/rss.xml",
    "reuters": "https://feeds.reuters.com/reuters/topNews",
    "techcrunch": "https://techcrunch.com/feed/",
    "the_verge": "https://www.theverge.com/rss/index.xml",
    "ars_technica": "http://feeds.arstechnica.com/arstechnica/index",
    "hackernews": "https://hnrss.org/frontpage",
}

CATEGORY_KEYWORDS: Dict[str, List[str]] = {
    "technology": ["ai", "tech", "software", "hardware", "startup", "app", "robot",
                   "code", "developer", "cybersecurity", "cloud", "blockchain"],
    "business": ["market", "stock", "economy", "trade", "company", "finance",
                 "investment", "revenue", "gdp", "inflation"],
    "science": ["research", "study", "climate", "space", "nasa", "discovery",
                "biology", "chemistry", "physics", "laboratory"],
    "health": ["health", "medical", "vaccine", "hospital", "cancer", "drug",
               "mental health", "nutrition", "fitness", "pandemic"],
    "sports": ["football", "cricket", "tennis", "basketball", "match", "tournament",
               "champion", "athlete", "olympic", "league"],
    "entertainment": ["movie", "music", "celebrity", "film", "concert", "award",
                      "streaming", "netflix", "spotify", "singer"],
    "politics": ["government", "election", "president", "parliament", "policy",
                 "minister", "senate", "congress", "vote", "law"],
    "world": ["war", "conflict", "peace", "un", "refugee", "humanitarian",
              "disaster", "earthquake", "flood", "global"],
    "environment": ["climate", "carbon", "emission", "renewable", "solar",
                    "wind energy", "deforestation", "wildlife", "plastic"],
}


@dataclass
class Article:
    title: str
    url: str
    source: str
    published_at: float = field(default_factory=time.time)
    description: str = ""
    content: str = ""
    category: str = "world"
    summary: str = ""
    fingerprint: str = ""

    def __post_init__(self):
        text = (self.title + " " + self.description).lower()
        self.category = _classify(text)
        self.fingerprint = hashlib.md5((self.title + self.url).encode()).hexdigest()[:12]
        if not self.summary:
            self.summary = _summarise(self.description or self.title, NEWS_SUMMARY_MAX_WORDS)

    def to_dict(self) -> Dict:
        return {
            "title": self.title, "url": self.url, "source": self.source,
            "published_at": self.published_at, "category": self.category,
            "summary": self.summary, "description": self.description,
        }


def _classify(text: str) -> str:
    scores: Dict[str, int] = {}
    for cat, keywords in CATEGORY_KEYWORDS.items():
        scores[cat] = sum(1 for kw in keywords if kw in text)
    return max(scores, key=scores.get) if max(scores.values()) > 0 else "world"


def _summarise(text: str, max_words: int) -> str:
    """Extractive summarisation — return first N words of the best sentence."""
    if not text:
        return ""
    sentences = [s.strip() for s in text.replace("\n", " ").split(".") if len(s.strip()) > 20]
    if not sentences:
        words = text.split()
        return " ".join(words[:max_words]) + ("..." if len(words) > max_words else "")
    # Score sentences by word overlap with title words
    result, count = [], 0
    for sent in sentences:
        words = sent.split()
        if count + len(words) > max_words:
            break
        result.append(sent)
        count += len(words)
    return ". ".join(result) + "." if result else sentences[0][:max_words * 6]


def _are_duplicates(a: Article, b: Article, threshold: float = 0.85) -> bool:
    ratio = SequenceMatcher(None, a.title.lower(), b.title.lower()).ratio()
    return ratio >= threshold


class NewsAggregator:
    """Multi-source news aggregation with deduplication, classification, and summarisation."""

    def __init__(self):
        self._cache: Dict[str, List[Article]] = {}
        self._cache_ts: float = 0.0

    # ── Fetch ─────────────────────────────────────────────────────────────────
    async def fetch(self, interests: Optional[List[str]] = None,
                    sources: Optional[List[str]] = None,
                    max_articles: int = NEWS_MAX_ARTICLES) -> List[Article]:
        if time.time() - self._cache_ts < NEWS_CACHE_TTL_S and self._cache.get("all"):
            articles = self._cache["all"]
            log.debug("News cache hit (%d articles)", len(articles))
        else:
            articles = await self._fetch_all()
            self._cache["all"] = articles
            self._cache_ts = time.time()

        # Filter by interests / sources
        if interests:
            articles = self._filter_by_interests(articles, interests)
        if sources:
            articles = [a for a in articles if any(s.lower() in a.source.lower() for s in sources)]

        return articles[:max_articles]

    async def _fetch_all(self) -> List[Article]:
        raw: List[Article] = []
        tasks = [self._fetch_newsapi(), self._fetch_rss()]
        results = await asyncio.gather(*tasks, return_exceptions=True)
        for r in results:
            if isinstance(r, list):
                raw.extend(r)
            elif isinstance(r, Exception):
                log.warning("Fetch error: %s", r)

        deduped = self._deduplicate(raw)
        deduped.sort(key=lambda a: a.published_at, reverse=True)
        log.info("Fetched %d unique articles (from %d raw)", len(deduped), len(raw))
        return deduped

    async def _fetch_newsapi(self) -> List[Article]:
        if not NEWS_API_KEY:
            log.debug("NEWS_API_KEY not set — skipping NewsAPI")
            return []
        try:
            import aiohttp
            url = "https://newsapi.org/v2/top-headlines"
            articles = []
            async with aiohttp.ClientSession() as session:
                for source_batch in [",".join(NEWS_SOURCES[:5]), ",".join(NEWS_SOURCES[5:])]:
                    if not source_batch.strip(","):
                        continue
                    params = {"sources": source_batch, "apiKey": NEWS_API_KEY, "pageSize": 20}
                    async with session.get(url, params=params) as resp:
                        if resp.status != 200:
                            continue
                        data = await resp.json()
                        for item in data.get("articles", []):
                            try:
                                import dateutil.parser
                                ts = dateutil.parser.parse(item.get("publishedAt", "")).timestamp()
                            except Exception:
                                ts = time.time()
                            articles.append(Article(
                                title=item.get("title", "") or "",
                                url=item.get("url", "") or "",
                                source=item.get("source", {}).get("name", "NewsAPI"),
                                published_at=ts,
                                description=item.get("description", "") or "",
                                content=item.get("content", "") or "",
                            ))
            log.debug("NewsAPI: fetched %d articles", len(articles))
            return articles
        except Exception as e:
            log.warning("NewsAPI fetch error: %s", e)
            return []

    async def _fetch_rss(self) -> List[Article]:
        try:
            import feedparser
        except ImportError:
            log.debug("feedparser not installed — skipping RSS feeds")
            return []
        articles = []
        loop = asyncio.get_running_loop()
        for name, url in RSS_FALLBACK_FEEDS.items():
            try:
                feed = await loop.run_in_executor(None, lambda u=url: feedparser.parse(u))
                for entry in (feed.entries or [])[:10]:
                    summary = getattr(entry, "summary", "") or ""
                    published = time.time()
                    if hasattr(entry, "published_parsed") and entry.published_parsed:
                        import calendar
                        published = float(calendar.timegm(entry.published_parsed))
                    articles.append(Article(
                        title=getattr(entry, "title", "") or "",
                        url=getattr(entry, "link", "") or "",
                        source=name.replace("_", " ").title(),
                        published_at=published,
                        description=summary[:500],
                    ))
            except Exception as e:
                log.warning("RSS %s error: %s", name, e)
        log.debug("RSS: fetched %d articles", len(articles))
        return articles

    # ── Deduplication ─────────────────────────────────────────────────────────
    def _deduplicate(self, articles: List[Article]) -> List[Article]:
        unique: List[Article] = []
        seen_fps: Set[str] = set()
        for a in articles:
            if a.fingerprint in seen_fps:
                continue
            if any(_are_duplicates(a, u) for u in unique[-20:]):   # check last 20 for speed
                continue
            unique.append(a)
            seen_fps.add(a.fingerprint)
        return unique

    # ── Interest filtering ────────────────────────────────────────────────────
    def _filter_by_interests(self, articles: List[Article],
                              interests: List[str]) -> List[Article]:
        norm = [i.lower() for i in interests]
        scored = []
        for a in articles:
            score = 0
            text = (a.title + " " + a.description + " " + a.category).lower()
            for interest in norm:
                if interest in text:
                    score += 1
            scored.append((score, a))
        scored.sort(key=lambda x: x[0], reverse=True)
        return [a for _, a in scored]

    # ── Combined briefing ─────────────────────────────────────────────────────
    async def get_briefing_summary(self, interests: Optional[List[str]] = None,
                                   max_per_category: int = 2) -> str:
        articles = await self.fetch(interests=interests, max_articles=NEWS_MAX_ARTICLES)
        by_cat: Dict[str, List[Article]] = {}
        for a in articles:
            by_cat.setdefault(a.category, []).append(a)

        lines = ["📰 **NEWS SUMMARY**\n"]
        for cat, items in by_cat.items():
            if not items:
                continue
            lines.append(f"\n**{cat.title()}**")
            for item in items[:max_per_category]:
                lines.append(f"• {item.title} — {item.summary[:100]}... ({item.source})")

        return "\n".join(lines)

    async def get_by_category(self, category: str,
                               max_articles: int = 10) -> List[Article]:
        all_articles = await self.fetch()
        return [a for a in all_articles if a.category == category.lower()][:max_articles]
