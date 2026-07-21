"""Citation extraction and confidence scoring for search results."""
from __future__ import annotations
import re
from typing import Any, Dict, List, Optional
from urllib.parse import urlparse


TRUSTED_DOMAINS = {
    "wikipedia.org": 0.95, "gov": 0.90, "edu": 0.88, "bbc.com": 0.85,
    "reuters.com": 0.85, "apnews.com": 0.85, "nature.com": 0.92,
    "scholar.google.com": 0.90, "arxiv.org": 0.88, "nytimes.com": 0.80,
    "theguardian.com": 0.80, "techcrunch.com": 0.70, "medium.com": 0.50,
}


def _domain_confidence(url: str) -> float:
    try:
        host = urlparse(url).netloc.lstrip("www.")
        for domain, score in TRUSTED_DOMAINS.items():
            if host.endswith(domain):
                return score
        tld = host.split(".")[-1]
        if tld in TRUSTED_DOMAINS:
            return TRUSTED_DOMAINS[tld]
    except Exception:
        pass
    return 0.6


def build_citations(results: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Add citation metadata to a list of search result dicts."""
    citations = []
    for i, r in enumerate(results, 1):
        url = r.get("url", "")
        citations.append({
            "index": i,
            "title": r.get("title", ""),
            "url": url,
            "snippet": r.get("snippet") or r.get("description", ""),
            "source": r.get("source", urlparse(url).netloc.lstrip("www.") if url else ""),
            "confidence": round(_domain_confidence(url), 2),
            "format": f"[{i}] {r.get('title','')} — {url}",
        })
    return citations


def inline_citations(text: str, citations: List[Dict]) -> str:
    """Insert [n] citation markers into a text response."""
    result = text
    for c in citations:
        domain = urlparse(c["url"]).netloc.lstrip("www.") if c.get("url") else ""
        if domain and domain in result:
            result = result.replace(domain, f"{domain} [{c['index']}]", 1)
    return result


def format_citation_block(citations: List[Dict]) -> str:
    """Return a formatted citation block string."""
    if not citations:
        return ""
    lines = ["\n\n**Sources:**"]
    for c in citations:
        lines.append(f"[{c['index']}] [{c['title']}]({c['url']}) — confidence: {c['confidence']:.0%}")
    return "\n".join(lines)
