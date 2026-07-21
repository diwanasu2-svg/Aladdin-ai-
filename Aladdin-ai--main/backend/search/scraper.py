"""Improved web scraper — extracts clean text from any URL."""
from __future__ import annotations
import asyncio
import logging
import re
from typing import Any, Dict, Optional

import aiohttp

log = logging.getLogger(__name__)

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                  "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.5",
}


async def scrape(url: str, max_chars: int = 5000, timeout: int = 15) -> Dict[str, Any]:
    """Fetch URL and return clean extracted text + metadata."""
    try:
        async with aiohttp.ClientSession(headers=HEADERS) as session:
            async with session.get(url, timeout=aiohttp.ClientTimeout(total=timeout),
                                   allow_redirects=True) as resp:
                content_type = resp.headers.get("content-type", "")
                if "html" not in content_type and "text" not in content_type:
                    return {"url": url, "text": "", "error": f"Non-HTML content: {content_type}"}
                html = await resp.text(errors="ignore")

        text = _extract_text(html)
        title = _extract_title(html)
        description = _extract_meta(html, "description")

        return {
            "url": url,
            "title": title,
            "description": description,
            "text": text[:max_chars],
            "text_length": len(text),
            "truncated": len(text) > max_chars,
        }
    except asyncio.TimeoutError:
        return {"url": url, "text": "", "error": "Request timed out"}
    except Exception as exc:
        return {"url": url, "text": "", "error": str(exc)}


def _extract_text(html: str) -> str:
    try:
        from bs4 import BeautifulSoup
        soup = BeautifulSoup(html, "html.parser")
        for tag in soup(["script", "style", "nav", "footer", "header", "aside", "iframe"]):
            tag.decompose()
        # Prefer article/main content
        for selector in ["article", "main", "[role='main']", ".content", "#content"]:
            el = soup.select_one(selector)
            if el:
                return " ".join(el.get_text(separator=" ").split())
        return " ".join(soup.get_text(separator=" ").split())
    except ImportError:
        text = re.sub(r"<script[^>]*>.*?</script>", " ", html, flags=re.DOTALL | re.IGNORECASE)
        text = re.sub(r"<style[^>]*>.*?</style>",  " ", text,  flags=re.DOTALL | re.IGNORECASE)
        text = re.sub(r"<[^>]+>", " ", text)
        return " ".join(text.split())


def _extract_title(html: str) -> str:
    m = re.search(r"<title[^>]*>(.*?)</title>", html, re.IGNORECASE | re.DOTALL)
    return m.group(1).strip() if m else ""


def _extract_meta(html: str, name: str) -> str:
    m = re.search(rf'<meta[^>]+name=["\']description["\'][^>]+content=["\'](.*?)["\']',
                  html, re.IGNORECASE)
    if not m:
        m = re.search(rf'<meta[^>]+content=["\'](.*?)["\'][^>]+name=["\']description["\']',
                      html, re.IGNORECASE)
    return m.group(1).strip() if m else ""
