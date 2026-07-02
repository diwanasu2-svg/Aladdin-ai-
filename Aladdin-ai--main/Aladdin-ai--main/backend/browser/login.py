"""Login automation — handles login forms, session persistence."""
from __future__ import annotations
import asyncio
import json
import logging
import os
from pathlib import Path
from typing import Dict, Optional

log = logging.getLogger(__name__)

SESSION_DIR = Path("data/browser_sessions")


class LoginAutomator:
    """Automates website login with credential management and session persistence."""

    def __init__(self, pw_manager) -> None:
        self._pw = pw_manager
        SESSION_DIR.mkdir(parents=True, exist_ok=True)

    def _session_file(self, site: str) -> Path:
        safe = "".join(c for c in site if c.isalnum() or c in "-_.")
        return SESSION_DIR / f"{safe}.json"

    async def login(self, url: str, username: str, password: str,
                    username_selector: str = "input[type='email'],input[type='text'],input[name='username'],input[name='email']",
                    password_selector: str = "input[type='password']",
                    submit_selector: str = "button[type='submit'],input[type='submit']",
                    success_selector: Optional[str] = None,
                    save_session: bool = True) -> Dict:
        page = await self._pw.new_page()
        if page is None:
            return {"success": False, "error": "Browser not available"}
        try:
            await page.goto(url, wait_until="networkidle", timeout=30000)
            # Try each selector option (comma-separated)
            async def _try_selectors(combined: str, action, value=None):
                for sel in combined.split(","):
                    sel = sel.strip()
                    try:
                        await page.wait_for_selector(sel, timeout=3000)
                        if value is not None:
                            await page.fill(sel, value)
                        else:
                            await page.click(sel)
                        return sel
                    except Exception:
                        continue
                return None

            await _try_selectors(username_selector, "fill", username)
            await asyncio.sleep(0.3)
            await _try_selectors(password_selector, "fill", password)
            await asyncio.sleep(0.3)
            await _try_selectors(submit_selector, "click")
            await page.wait_for_load_state("networkidle", timeout=15000)

            # Check login success
            success = True
            if success_selector:
                try:
                    await page.wait_for_selector(success_selector, timeout=8000)
                except Exception:
                    success = False

            current_url = page.url
            if save_session and success:
                session_file = self._session_file(url)
                await self._pw._context.storage_state(path=str(session_file))
                log.info("Session saved: %s", session_file)

            return {"success": success, "url": current_url, "title": await page.title()}
        except Exception as exc:
            log.error("Login error: %s", exc)
            return {"success": False, "error": str(exc)}
        finally:
            await page.close()

    async def load_session(self, site: str) -> bool:
        session_file = self._session_file(site)
        if session_file.exists():
            await self._pw.load_storage(str(session_file))
            log.info("Session loaded: %s", session_file)
            return True
        return False
