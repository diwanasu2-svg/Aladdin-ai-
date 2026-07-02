"""
Phase 8.9 — Browser Memory
============================
Remember visited sites, store searches/tasks, save tabs/sessions,
maintain user preferences, connect browser history to AI memory.
"""
from __future__ import annotations
import json, logging, os, threading, time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

log = logging.getLogger(__name__)

_DATA_FILE = Path("data/browser_memory.json")
_DATA_FILE.parent.mkdir(parents=True, exist_ok=True)


@dataclass
class SiteVisit:
    url: str
    title: str
    visit_count: int = 1
    last_visited: float = field(default_factory=time.time)
    first_visited: float = field(default_factory=time.time)
    tags: List[str] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


@dataclass
class SearchRecord:
    query: str
    engine: str
    url: str
    timestamp: float = field(default_factory=time.time)
    result_count: int = 0

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


@dataclass
class BrowsingTask:
    task_id: str
    description: str
    urls_visited: List[str]
    started_at: float = field(default_factory=time.time)
    completed_at: Optional[float] = None
    outcome: str = ""
    success: bool = False

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


@dataclass
class SavedTab:
    tab_id: str
    url: str
    title: str
    saved_at: float = field(default_factory=time.time)
    profile: str = "default"

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


class BrowserMemory:
    """
    Persistent browser memory — visits, searches, tasks, tabs, preferences.

    Usage::

        mem = BrowserMemory()
        mem.set_ai_memory_fn(lambda key, val: ai_memory.store(key, val))
        mem.record_visit(url, title)
        top = mem.top_visited(10)
        mem.save()
    """

    def __init__(self, data_path: str = "data/browser_memory.json") -> None:
        self._path = Path(data_path)
        self._path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._visits: Dict[str, SiteVisit] = {}
        self._searches: List[SearchRecord] = []
        self._tasks: Dict[str, BrowsingTask] = {}
        self._saved_tabs: List[SavedTab] = []
        self._preferences: Dict[str, Any] = {}
        self._ai_fn: Optional[Callable[[str, Any], None]] = None
        self._load()
        log.info("BrowserMemory initialised")

    def set_ai_memory_fn(self, fn: Callable[[str, Any], None]) -> None:
        self._ai_fn = fn

    def _ai_store(self, key: str, value: Any) -> None:
        if self._ai_fn:
            try:
                self._ai_fn(key, value)
            except Exception as exc:
                log.debug("AI memory store error: %s", exc)

    # ── Persistence ───────────────────────────────────────────────────────────

    def _load(self) -> None:
        if not self._path.exists():
            return
        try:
            with open(self._path, "r", encoding="utf-8") as f:
                data = json.load(f)
            with self._lock:
                for url, v in data.get("visits", {}).items():
                    self._visits[url] = SiteVisit(**v)
                for s in data.get("searches", []):
                    self._searches.append(SearchRecord(**s))
                for tid, t in data.get("tasks", {}).items():
                    self._tasks[tid] = BrowsingTask(**t)
                for tab in data.get("saved_tabs", []):
                    self._saved_tabs.append(SavedTab(**tab))
                self._preferences = data.get("preferences", {})
            log.info("BrowserMemory: loaded %d sites, %d searches, %d tasks",
                     len(self._visits), len(self._searches), len(self._tasks))
        except Exception as exc:
            log.error("BrowserMemory load error: %s", exc)

    def save(self) -> None:
        try:
            with self._lock:
                data = {
                    "visits": {url: asdict(v) for url, v in self._visits.items()},
                    "searches": [asdict(s) for s in self._searches[-500:]],
                    "tasks": {tid: asdict(t) for tid, t in self._tasks.items()},
                    "saved_tabs": [asdict(t) for t in self._saved_tabs],
                    "preferences": self._preferences,
                    "saved_at": time.time(),
                }
            with open(self._path, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=2)
        except Exception as exc:
            log.error("BrowserMemory save error: %s", exc)

    # ── Site visits ───────────────────────────────────────────────────────────

    def record_visit(self, url: str, title: str = "", tags: Optional[List[str]] = None) -> None:
        with self._lock:
            if url in self._visits:
                self._visits[url].visit_count += 1
                self._visits[url].last_visited = time.time()
                if title:
                    pass  # keep original title
                if tags:
                    for t in tags:
                        if t not in self._visits[url].tags:
                            self._visits[url].tags.append(t)
            else:
                self._visits[url] = SiteVisit(url=url, title=title, tags=tags or [])
        self._ai_store(f"browser.visits.{url}", {"url": url, "title": title})

    def top_visited(self, n: int = 10) -> List[Dict[str, Any]]:
        with self._lock:
            sorted_visits = sorted(self._visits.values(),
                                   key=lambda v: v.visit_count, reverse=True)
        return [v.to_dict() for v in sorted_visits[:n]]

    def recent_visits(self, n: int = 20) -> List[Dict[str, Any]]:
        with self._lock:
            sorted_visits = sorted(self._visits.values(),
                                   key=lambda v: v.last_visited, reverse=True)
        return [v.to_dict() for v in sorted_visits[:n]]

    def search_history(self, query: str) -> List[Dict[str, Any]]:
        """Find visits whose URL or title contains query."""
        q = query.lower()
        with self._lock:
            return [v.to_dict() for v in self._visits.values()
                    if q in v.url.lower() or q in v.title.lower()]

    # ── Search records ────────────────────────────────────────────────────────

    def record_search(self, query: str, engine: str = "google",
                       url: str = "", result_count: int = 0) -> None:
        rec = SearchRecord(query=query, engine=engine, url=url, result_count=result_count)
        with self._lock:
            self._searches.append(rec)
        self._ai_store(f"browser.search.{int(time.time())}", asdict(rec))

    def recent_searches(self, n: int = 20) -> List[Dict[str, Any]]:
        with self._lock:
            return [asdict(s) for s in self._searches[-n:][::-1]]

    # ── Browsing tasks ────────────────────────────────────────────────────────

    def start_task(self, description: str) -> str:
        task_id = f"task_{int(time.time() * 1000)}"
        task = BrowsingTask(task_id=task_id, description=description, urls_visited=[])
        with self._lock:
            self._tasks[task_id] = task
        return task_id

    def record_task_url(self, task_id: str, url: str) -> None:
        with self._lock:
            if task_id in self._tasks:
                self._tasks[task_id].urls_visited.append(url)

    def complete_task(self, task_id: str, outcome: str = "", success: bool = True) -> None:
        with self._lock:
            if task_id in self._tasks:
                self._tasks[task_id].completed_at = time.time()
                self._tasks[task_id].outcome = outcome
                self._tasks[task_id].success = success
        self._ai_store(f"browser.task.{task_id}", {"outcome": outcome, "success": success})

    def get_task(self, task_id: str) -> Optional[Dict[str, Any]]:
        with self._lock:
            t = self._tasks.get(task_id)
        return t.to_dict() if t else None

    def recent_tasks(self, n: int = 10) -> List[Dict[str, Any]]:
        with self._lock:
            tasks = sorted(self._tasks.values(), key=lambda t: t.started_at, reverse=True)
        return [t.to_dict() for t in tasks[:n]]

    # ── Saved tabs ────────────────────────────────────────────────────────────

    def save_tab(self, tab_id: str, url: str, title: str, profile: str = "default") -> None:
        tab = SavedTab(tab_id=tab_id, url=url, title=title, profile=profile)
        with self._lock:
            self._saved_tabs = [t for t in self._saved_tabs if t.tab_id != tab_id]
            self._saved_tabs.append(tab)

    def list_saved_tabs(self, profile: Optional[str] = None) -> List[Dict[str, Any]]:
        with self._lock:
            tabs = self._saved_tabs
        if profile:
            tabs = [t for t in tabs if t.profile == profile]
        return [t.to_dict() for t in tabs]

    def remove_tab(self, tab_id: str) -> None:
        with self._lock:
            self._saved_tabs = [t for t in self._saved_tabs if t.tab_id != tab_id]

    # ── Preferences ───────────────────────────────────────────────────────────

    def set_preference(self, key: str, value: Any) -> None:
        with self._lock:
            self._preferences[key] = value

    def get_preference(self, key: str, default: Any = None) -> Any:
        with self._lock:
            return self._preferences.get(key, default)

    def all_preferences(self) -> Dict[str, Any]:
        with self._lock:
            return dict(self._preferences)

    # ── Summary ───────────────────────────────────────────────────────────────

    def summary(self) -> Dict[str, Any]:
        with self._lock:
            return {
                "total_sites_visited": len(self._visits),
                "total_searches": len(self._searches),
                "total_tasks": len(self._tasks),
                "saved_tabs": len(self._saved_tabs),
                "top_visited": self.top_visited(5),
                "recent_searches": self.recent_searches(5),
            }
