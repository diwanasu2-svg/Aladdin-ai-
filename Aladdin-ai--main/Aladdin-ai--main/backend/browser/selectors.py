"""
Phase 8.3 — Smart Selectors
=============================
Stable element finding: try multiple strategies, fallback chains,
text-based, attribute-based, hierarchy-based matching.
"""
from __future__ import annotations
import asyncio, logging
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple

log = logging.getLogger(__name__)


@dataclass
class SelectorResult:
    found: bool
    element: Any = None        # Playwright ElementHandle
    selector_used: str = ""
    strategy: str = ""
    attempts: int = 0

    def to_dict(self) -> Dict[str, Any]:
        return {
            "found": self.found,
            "selector_used": self.selector_used,
            "strategy": self.strategy,
            "attempts": self.attempts,
        }


class SmartSelector:
    """
    Multi-strategy element finder with fallback chain.

    Priority:
    1. Primary CSS selector
    2. Attribute variants (id, name, placeholder, aria-label, data-*)
    3. Text-content match
    4. XPath
    5. Role + accessible name (ARIA)
    """

    def __init__(self, timeout_ms: int = 5000) -> None:
        self._timeout = timeout_ms

    async def find(self, page, primary_selector: str,
                   fallbacks: Optional[List[str]] = None,
                   text: Optional[str] = None,
                   role: Optional[str] = None,
                   name: Optional[str] = None) -> SelectorResult:
        """
        Find element using primary selector, then fallbacks.
        """
        all_selectors = [primary_selector] + (fallbacks or [])
        attempts = 0

        # 1. CSS selectors
        for sel in all_selectors:
            attempts += 1
            el = await self._try_css(page, sel)
            if el:
                return SelectorResult(True, el, sel, "css", attempts)

        # 2. Text content match
        if text:
            attempts += 1
            el, sel = await self._by_text(page, text)
            if el:
                return SelectorResult(True, el, sel, "text", attempts)

        # 3. ARIA role + name
        if role:
            attempts += 1
            el, sel = await self._by_role(page, role, name)
            if el:
                return SelectorResult(True, el, sel, "aria", attempts)

        # 4. Attribute-based derivations from primary
        attempts += 1
        el, sel = await self._by_attributes(page, primary_selector)
        if el:
            return SelectorResult(True, el, sel, "attributes", attempts)

        return SelectorResult(False, None, primary_selector, "none", attempts)

    async def find_all(self, page, selector: str,
                       fallbacks: Optional[List[str]] = None) -> List[Any]:
        """Find all matching elements trying each selector."""
        for sel in [selector] + (fallbacks or []):
            try:
                els = await page.query_selector_all(sel)
                if els:
                    return els
            except Exception:
                continue
        return []

    async def wait_for(self, page, selector: str,
                       fallbacks: Optional[List[str]] = None,
                       state: str = "visible") -> SelectorResult:
        """Wait for element to reach desired state."""
        all_selectors = [selector] + (fallbacks or [])
        for sel in all_selectors:
            try:
                el = await page.wait_for_selector(sel, state=state, timeout=self._timeout)
                if el:
                    return SelectorResult(True, el, sel, "wait_for", 1)
            except Exception:
                continue
        return SelectorResult(False)

    # ── Strategy implementations ──────────────────────────────────────────────

    async def _try_css(self, page, selector: str) -> Optional[Any]:
        try:
            el = await page.query_selector(selector)
            if el and await el.is_visible():
                return el
        except Exception:
            pass
        return None

    async def _by_text(self, page, text: str) -> Tuple[Optional[Any], str]:
        strategies = [
            f"text={text}",
            f"//*[contains(text(), '{text}')]",
            f"[aria-label*='{text}']",
            f"[placeholder*='{text}']",
            f"[title*='{text}']",
            f"[value*='{text}']",
        ]
        for sel in strategies:
            try:
                el = await page.query_selector(sel)
                if el and await el.is_visible():
                    return el, sel
            except Exception:
                continue
        return None, ""

    async def _by_role(self, page, role: str, name: Optional[str] = None) -> Tuple[Optional[Any], str]:
        sel = f"role={role}"
        if name:
            sel += f"[name='{name}']"
        try:
            el = await page.query_selector(sel)
            if el:
                return el, sel
        except Exception:
            pass
        # Fallback: semantic tags
        tag_map = {
            "button": "button, input[type='button'], input[type='submit']",
            "textbox": "input[type='text'], input[type='email'], textarea",
            "checkbox": "input[type='checkbox']",
            "radio": "input[type='radio']",
            "combobox": "select",
            "link": "a[href]",
        }
        if role in tag_map:
            try:
                el = await page.query_selector(tag_map[role])
                if el:
                    return el, tag_map[role]
            except Exception:
                pass
        return None, ""

    async def _by_attributes(self, page, hint: str) -> Tuple[Optional[Any], str]:
        """Derive attribute selectors from a CSS hint string."""
        # Extract bare word (e.g. "username" from "#username" or ".username")
        word = hint.lstrip("#.[]").split("[")[0].split("]")[0]
        attrs = [
            f"[id*='{word}']", f"[name*='{word}']",
            f"[placeholder*='{word}']", f"[aria-label*='{word}']",
            f"[data-testid*='{word}']", f"[class*='{word}']",
        ]
        for sel in attrs:
            try:
                el = await page.query_selector(sel)
                if el and await el.is_visible():
                    return el, sel
            except Exception:
                continue
        return None, ""

    # ── Convenience builders ──────────────────────────────────────────────────

    @staticmethod
    def build_input_selectors(field_name: str) -> List[str]:
        """Return a list of selectors for a named input field."""
        n = field_name.lower()
        return [
            f"input[name='{n}']", f"input[id='{n}']",
            f"input[placeholder*='{n}']", f"input[aria-label*='{n}']",
            f"#{n}", f".{n}",
            f"input[name*='{n}']", f"textarea[name='{n}']",
        ]

    @staticmethod
    def build_button_selectors(label: str) -> List[str]:
        """Return selectors for a button with a given label."""
        l = label.lower()
        return [
            f"button:has-text('{label}')",
            f"input[value='{label}']",
            f"button[aria-label*='{l}']",
            f"[role='button']:has-text('{label}')",
            f"a:has-text('{label}')",
            "button[type='submit']",
        ]
