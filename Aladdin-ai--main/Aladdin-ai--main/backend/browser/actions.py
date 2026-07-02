"""Browser action helpers — click, fill, scroll, wait."""
from __future__ import annotations
import asyncio
import logging
from typing import Any, Dict, List, Optional

log = logging.getLogger(__name__)


class BrowserActions:
    """High-level browser actions wrapping Playwright page methods."""

    def __init__(self, page) -> None:
        self._page = page

    async def click(self, selector: str, timeout: int = 5000) -> bool:
        try:
            await self._page.wait_for_selector(selector, timeout=timeout)
            await self._page.click(selector)
            return True
        except Exception as exc:
            log.warning("Click failed [%s]: %s", selector, exc)
            return False

    async def fill(self, selector: str, value: str, timeout: int = 5000) -> bool:
        try:
            await self._page.wait_for_selector(selector, timeout=timeout)
            await self._page.fill(selector, value)
            return True
        except Exception as exc:
            log.warning("Fill failed [%s]: %s", selector, exc)
            return False

    async def select_option(self, selector: str, value: str) -> bool:
        try:
            await self._page.select_option(selector, value=value)
            return True
        except Exception as exc:
            log.warning("Select failed [%s]: %s", selector, exc)
            return False

    async def check(self, selector: str) -> bool:
        try:
            await self._page.check(selector)
            return True
        except Exception as exc:
            log.warning("Check failed [%s]: %s", selector, exc)
            return False

    async def scroll_to_bottom(self) -> None:
        await self._page.evaluate("window.scrollTo(0, document.body.scrollHeight)")
        await asyncio.sleep(0.5)

    async def scroll_by(self, x: int = 0, y: int = 500) -> None:
        await self._page.evaluate(f"window.scrollBy({x}, {y})")

    async def wait_for(self, selector: str, timeout: int = 10000) -> bool:
        try:
            await self._page.wait_for_selector(selector, timeout=timeout)
            return True
        except Exception:
            return False

    async def wait_for_navigation(self, timeout: int = 15000) -> bool:
        try:
            await self._page.wait_for_load_state("networkidle", timeout=timeout)
            return True
        except Exception:
            return False

    async def get_text(self, selector: str) -> Optional[str]:
        try:
            return await self._page.text_content(selector)
        except Exception:
            return None

    async def get_attribute(self, selector: str, attr: str) -> Optional[str]:
        try:
            return await self._page.get_attribute(selector, attr)
        except Exception:
            return None

    async def handle_dialog(self, action: str = "accept") -> None:
        async def _handler(dialog):
            if action == "accept":
                await dialog.accept()
            else:
                await dialog.dismiss()
        self._page.on("dialog", _handler)

    async def fill_form(self, fields: Dict[str, str]) -> Dict[str, bool]:
        """Fill multiple form fields. keys=selectors, values=text."""
        results = {}
        for selector, value in fields.items():
            results[selector] = await self.fill(selector, value)
        return results
