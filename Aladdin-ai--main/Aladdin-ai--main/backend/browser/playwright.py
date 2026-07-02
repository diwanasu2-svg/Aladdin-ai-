"""Playwright setup and browser lifecycle management."""
from __future__ import annotations
import logging
from typing import Optional

log = logging.getLogger(__name__)

try:
    from playwright.async_api import async_playwright, Browser, BrowserContext, Page, Playwright
    _PW_AVAILABLE = True
except ImportError:
    _PW_AVAILABLE = False
    log.warning("playwright not installed — browser automation unavailable. Run: pip install playwright && playwright install chromium")


class PlaywrightManager:
    """Manages the Playwright browser lifecycle."""

    def __init__(self) -> None:
        self._pw: Optional["Playwright"] = None
        self._browser: Optional["Browser"] = None
        self._context: Optional["BrowserContext"] = None

    @property
    def available(self) -> bool:
        return _PW_AVAILABLE

    async def start(self, headless: bool = True, slow_mo: int = 0) -> bool:
        if not _PW_AVAILABLE:
            return False
        try:
            self._pw = await async_playwright().start()
            self._browser = await self._pw.chromium.launch(headless=headless, slow_mo=slow_mo)
            self._context = await self._browser.new_context(
                viewport={"width": 1280, "height": 720},
                user_agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
            )
            log.info("Playwright Chromium launched (headless=%s)", headless)
            return True
        except Exception as exc:
            log.error("Playwright launch failed: %s", exc)
            return False

    async def new_page(self) -> Optional["Page"]:
        if self._context is None:
            await self.start()
        if self._context is None:
            return None
        return await self._context.new_page()

    async def close(self) -> None:
        try:
            if self._context:
                await self._context.close()
            if self._browser:
                await self._browser.close()
            if self._pw:
                await self._pw.stop()
        except Exception as exc:
            log.warning("Playwright close error: %s", exc)
        finally:
            self._pw = self._browser = self._context = None

    async def save_storage(self, path: str) -> None:
        if self._context:
            await self._context.storage_state(path=path)

    async def load_storage(self, path: str) -> None:
        """Load stored cookies/session from a previous run."""
        if not _PW_AVAILABLE or self._browser is None:
            return
        import os
        if os.path.exists(path):
            self._context = await self._browser.new_context(storage_state=path)
