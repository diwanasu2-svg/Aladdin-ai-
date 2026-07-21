"""Web search API routes."""
from __future__ import annotations
import logging
from typing import Any, Dict, List, Optional
from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

log = logging.getLogger(__name__)
router = APIRouter(prefix="/search", tags=["Search"])


class SearchRequest(BaseModel):
    query: str
    engine: str = "auto"          # auto / brave / google / duckduckgo
    max_results: int = 10
    include_citations: bool = True
    scrape_top: int = 0           # Number of top results to scrape for full text


class BraveRequest(BaseModel):
    query: str
    count: int = 10
    country: str = "US"


class GoogleRequest(BaseModel):
    query: str
    num: int = 10
    search_type: Optional[str] = None  # None or "image"


class NewsRequest(BaseModel):
    query: Optional[str] = None
    category: str = "general"
    country: str = "us"
    source: str = "newsapi"      # newsapi / rss
    rss_url: Optional[str] = None
    page_size: int = 10
    sort_by: str = "publishedAt"


def _engine():
    from ..main import app_state
    e = app_state.get("search_engine")
    if not e:
        raise HTTPException(503, "Search engine not initialized")
    return e


@router.post("")
async def search(req: SearchRequest):
    return await _engine().search(
        req.query, engine=req.engine, max_results=req.max_results,
        include_citations=req.include_citations, scrape_top=req.scrape_top)


@router.post("/stream")
async def stream_search(req: SearchRequest):
    """Streaming search via SSE — results appear progressively."""
    engine = _engine()

    async def event_gen():
        async for chunk in engine.stream_search(
            req.query, engine=req.engine, max_results=req.max_results
        ):
            yield chunk

    return StreamingResponse(event_gen(), media_type="text/event-stream",
                              headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@router.get("/result/{result_id}")
async def get_result(result_id: str):
    r = _engine().get_result(result_id)
    if not r:
        raise HTTPException(404, "Result not found (may have expired)")
    return r


@router.get("/suggestions")
async def suggestions(q: str = Query(..., min_length=1)):
    return {"query": q, "suggestions": await _engine().suggestions(q)}


@router.get("/history")
async def history(limit: int = Query(50)):
    return {"history": _engine().get_history(limit)}


@router.post("/brave")
async def brave_search(req: BraveRequest):
    from ..main import app_state
    engine = app_state.get("search_engine")
    if not engine or not engine.brave.available:
        raise HTTPException(503, "Brave Search API key not set (BRAVE_SEARCH_API_KEY)")
    return await engine.brave.web(req.query, count=req.count, country=req.country)


@router.post("/brave/news")
async def brave_news(req: BraveRequest):
    from ..main import app_state
    engine = app_state.get("search_engine")
    if not engine or not engine.brave.available:
        raise HTTPException(503, "Brave Search API key not set")
    return await engine.brave.news(req.query, count=req.count)


@router.post("/brave/images")
async def brave_images(req: BraveRequest):
    from ..main import app_state
    engine = app_state.get("search_engine")
    if not engine or not engine.brave.available:
        raise HTTPException(503, "Brave Search API key not set")
    return await engine.brave.images(req.query, count=req.count)


@router.post("/google")
async def google_search(req: GoogleRequest):
    from ..main import app_state
    engine = app_state.get("search_engine")
    if not engine or not engine.google.available:
        raise HTTPException(503, "Google CSE not configured (GOOGLE_CSE_API_KEY + GOOGLE_CSE_CX)")
    return await engine.google.search(req.query, num=req.num, search_type=req.search_type)


@router.post("/news")
async def news_search(req: NewsRequest):
    from ..main import app_state
    engine = app_state.get("search_engine")
    if not engine:
        raise HTTPException(503, "Search engine not initialized")
    if req.source == "rss" and req.rss_url:
        from ..search.news import rss_news
        articles = await rss_news(req.rss_url)
        return {"articles": articles, "source": "rss"}
    if not engine.news.available:
        raise HTTPException(503, "NewsAPI key not set (NEWSAPI_KEY). Get one free at newsapi.org")
    if req.query:
        return await engine.news.everything(req.query, sort_by=req.sort_by, page_size=req.page_size)
    return await engine.news.top_headlines(category=req.category, country=req.country,
                                            page_size=req.page_size)


@router.post("/scrape")
async def scrape_url(url: str, max_chars: int = 5000):
    from ..search.scraper import scrape
    result = await scrape(url, max_chars=max_chars)
    if result.get("error"):
        raise HTTPException(502, result["error"])
    return result
