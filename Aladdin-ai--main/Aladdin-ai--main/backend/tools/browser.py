"""Browser tool — web search + page content extraction."""
from __future__ import annotations
import asyncio, logging, time
from typing import Optional
import aiohttp
from .base import BaseTool, ToolResult

log = logging.getLogger(__name__)

try:
    from bs4 import BeautifulSoup
    _BS4 = True
except ImportError:
    _BS4 = False


class WebSearchTool(BaseTool):
    name = "web_search"
    description = "Search the web using DuckDuckGo and return top results."
    parameters = {"type": "object", "properties": {
        "query": {"type": "string"}, "max_results": {"type": "integer", "default": 5}},
        "required": ["query"]}

    async def execute(self, query: str, max_results: int = 5) -> ToolResult:
        t0 = time.time()
        try:
            # DuckDuckGo Instant Answer API (free, no key)
            async with aiohttp.ClientSession() as s:
                r = await s.get("https://api.duckduckgo.com/", params={
                    "q": query, "format": "json", "no_html": 1, "skip_disambig": 1},
                    timeout=aiohttp.ClientTimeout(total=10))
                d = await r.json(content_type=None)
            results = []
            if d.get("AbstractText"):
                results.append({"title": d.get("Heading", ""), "snippet": d["AbstractText"],
                                 "url": d.get("AbstractURL", "")})
            for rel in d.get("RelatedTopics", [])[:max_results-len(results)]:
                if isinstance(rel, dict) and rel.get("Text"):
                    results.append({"title": rel.get("Text", "")[:80],
                                    "snippet": rel.get("Text", ""), "url": rel.get("FirstURL", "")})
            return ToolResult(True, self.name, {"query": query, "results": results},
                              duration_ms=(time.time()-t0)*1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time()-t0)*1000)


class ReadPageTool(BaseTool):
    name = "read_webpage"
    description = "Fetch a URL and extract its text content."
    parameters = {"type": "object", "properties": {
        "url": {"type": "string"}, "max_chars": {"type": "integer", "default": 3000}},
        "required": ["url"]}

    async def execute(self, url: str, max_chars: int = 3000) -> ToolResult:
        t0 = time.time()
        try:
            headers = {"User-Agent": "Mozilla/5.0 (compatible; Aladdin/2.0)"}
            async with aiohttp.ClientSession() as s:
                r = await s.get(url, headers=headers, timeout=aiohttp.ClientTimeout(total=15))
                html = await r.text()
            if _BS4:
                soup = BeautifulSoup(html, "html.parser")
                for tag in soup(["script","style","nav","footer","header"]):
                    tag.decompose()
                text = " ".join(soup.get_text(separator=" ").split())
            else:
                import re
                text = re.sub(r"<[^>]+>", " ", html)
                text = " ".join(text.split())
            return ToolResult(True, self.name, {
                "url": url, "text": text[:max_chars],
                "truncated": len(text) > max_chars, "length": len(text)},
                duration_ms=(time.time()-t0)*1000)
        except Exception as exc:
            return ToolResult(False, self.name, error=str(exc), duration_ms=(time.time()-t0)*1000)
