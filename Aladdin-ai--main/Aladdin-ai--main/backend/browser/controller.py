"""Browser controller — Phase 6 + Phase 8 unified browser automation API."""
from __future__ import annotations
import asyncio, base64, logging
from typing import Any, Callable, Dict, List, Optional

log = logging.getLogger(__name__)


class BrowserController:
    """Unified interface for all browser automation (Phase 6 + Phase 8)."""

    def __init__(self) -> None:
        from .playwright import PlaywrightManager
        from .login import LoginAutomator

        self._pw = PlaywrightManager()
        self._login_automator = LoginAutomator(self._pw)
        self._pages: Dict[str, Any] = {}       # tab_id → page

        # ── Phase 8 modules ──────────────────────────────────────────────
        from .captcha import CaptchaHandler
        from .antibot import AntiBotEvasion
        from .selectors import SmartSelector
        from .retry import RetryManager
        from .session_manager import SessionManager
        from .download_manager import DownloadManager
        from .upload_manager import UploadManager
        from .browser_memory import BrowserMemory
        from .profiles import ProfileManager

        self._captcha = CaptchaHandler()
        self._antibot = AntiBotEvasion()
        self._selector = SmartSelector()
        self._retry = RetryManager()
        self._session_mgr = SessionManager(self._pw)
        self._download_mgr = DownloadManager()
        self._upload_mgr = UploadManager()
        self._memory = BrowserMemory()
        self._profiles = ProfileManager()

    def configure_notifications(self, captcha_notify_fn: Optional[Callable] = None,
                                 mfa_prompt_fn: Optional[Callable] = None,
                                 upload_confirm_fn: Optional[Callable] = None,
                                 download_memory_fn: Optional[Callable] = None,
                                 ai_memory_fn: Optional[Callable] = None) -> None:
        """Wire up user-facing callbacks."""
        if captcha_notify_fn:
            self._captcha._notify = captcha_notify_fn
        if mfa_prompt_fn:
            self._session_mgr._mfa_prompt = mfa_prompt_fn
        if upload_confirm_fn:
            self._upload_mgr._confirm = upload_confirm_fn
        if download_memory_fn:
            self._download_mgr.set_memory_fn(download_memory_fn)
        if ai_memory_fn:
            self._memory.set_ai_memory_fn(ai_memory_fn)

    @property
    def available(self) -> bool:
        return self._pw.available

    # ── Page management ───────────────────────────────────────────────────────

    async def ensure_started(self) -> bool:
        if self._pw._browser is None:
            return await self._pw.start()
        return True

    async def _get_or_create_page(self, tab_id: str) -> Optional[Any]:
        if not await self.ensure_started():
            return None
        page = self._pages.get(tab_id)
        if page is None:
            page = await self._pw.new_page()
            self._pages[tab_id] = page
            # Attach stealth and download handler
            await self._antibot.stealth_setup(page)
            page.on("download", self._download_mgr.on_download_event)
        return page

    # ── Phase 6 — Navigation ──────────────────────────────────────────────────

    async def navigate(self, url: str, tab_id: str = "default",
                       wait_for: str = "networkidle",
                       anti_bot: bool = True,
                       handle_captcha: bool = True,
                       retry: bool = True) -> Dict[str, Any]:
        page = await self._get_or_create_page(tab_id)
        if page is None:
            return {"success": False, "error": "Browser not available. Install playwright."}

        await self._antibot.respect_rate_limit()
        task_id = self._memory.start_task(f"Navigate to {url}")

        if retry:
            result = await self._retry.navigate_with_retry(page, url, wait_until=wait_for)
            success = result.success
            error = result.last_error
        else:
            try:
                await page.goto(url, wait_until=wait_for, timeout=30000)
                success, error = True, ""
            except Exception as exc:
                success, error = False, str(exc)

        if success:
            title = await page.title()
            self._memory.record_visit(url, title)
            self._memory.record_task_url(task_id, url)
            self._memory.complete_task(task_id, f"Navigated to {url}", success=True)
            self._profiles.append_history(self._profiles.active_id, url, title)

            if handle_captcha:
                cap_result = await self._captcha.check_and_handle(page, f"navigate to {url}")
                if cap_result.detected and not cap_result.solved:
                    return {"success": False, "error": "CAPTCHA not solved",
                            "captcha": cap_result.to_dict()}

            if anti_bot:
                blocked, reason = await self._antibot.is_blocked(page)
                if blocked:
                    ok = await self._antibot.handle_rate_limit(page)
                    if not ok:
                        return {"success": False, "error": f"Blocked: {reason}"}

            return {"success": True, "url": page.url,
                    "title": await page.title(), "tab_id": tab_id}
        else:
            self._memory.complete_task(task_id, f"Failed: {error}", success=False)
            return {"success": False, "error": error}

    async def screenshot(self, tab_id: str = "default",
                          full_page: bool = True, selector: Optional[str] = None) -> Dict:
        page = self._pages.get(tab_id)
        if page is None:
            return {"success": False, "error": "No active page. Call navigate first."}
        try:
            if selector:
                res = await self._selector.find(page, selector)
                el = res.element if res.found else None
                png = await el.screenshot() if el else await page.screenshot(full_page=full_page)
            else:
                png = await page.screenshot(full_page=full_page)
            b64 = base64.b64encode(png).decode()
            return {"success": True, "image_base64": b64, "size_bytes": len(png), "url": page.url}
        except Exception as exc:
            return {"success": False, "error": str(exc)}

    async def get_content(self, tab_id: str = "default", extract_text: bool = True) -> Dict:
        page = self._pages.get(tab_id)
        if page is None:
            return {"success": False, "error": "No active page."}
        try:
            html = await page.content()
            text = ""
            if extract_text:
                text = await page.evaluate("""() => {
                    const clone = document.cloneNode(true);
                    const scripts = clone.querySelectorAll('script,style,nav,footer');
                    scripts.forEach(s => s.remove());
                    return document.body ? document.body.innerText : '';
                }""")
                text = " ".join(text.split())
            return {"success": True, "url": page.url, "title": await page.title(),
                    "text": text[:5000], "html_length": len(html)}
        except Exception as exc:
            return {"success": False, "error": str(exc)}

    async def click(self, selector: str, tab_id: str = "default",
                    human_like: bool = True) -> Dict:
        page = self._pages.get(tab_id)
        if page is None:
            return {"success": False, "error": "No active page."}
        if human_like:
            ok = await self._antibot.human_click(page, selector)
        else:
            from .actions import BrowserActions
            ok = await BrowserActions(page).click(selector)
        return {"success": ok, "selector": selector}

    async def fill_form(self, fields: Dict[str, str], tab_id: str = "default",
                        submit_selector: Optional[str] = None) -> Dict:
        page = self._pages.get(tab_id)
        if page is None:
            return {"success": False, "error": "No active page."}
        from .actions import BrowserActions
        actions = BrowserActions(page)
        results = await actions.fill_form(fields)
        submitted = False
        if submit_selector:
            submitted = await actions.click(submit_selector)
            await actions.wait_for_navigation()
        return {"success": all(results.values()), "fields": results, "submitted": submitted}

    async def login(self, url: str, username: str, password: str, **kwargs) -> Dict:
        result = await self._login_automator.login(url, username, password, **kwargs)
        if result.get("success") and kwargs.get("save_session", True):
            self._session_mgr.store_credentials(url, username, password)
            await self._session_mgr.save_session(url, username)
        return result

    async def close_tab(self, tab_id: str = "default") -> None:
        page = self._pages.pop(tab_id, None)
        if page:
            await page.close()

    async def close(self) -> None:
        for page in self._pages.values():
            try:
                await page.close()
            except Exception:
                pass
        self._pages.clear()
        await self._pw.close()

    def list_tabs(self) -> List[str]:
        return list(self._pages.keys())

    # ── Phase 8.1 — CAPTCHA ───────────────────────────────────────────────────

    async def check_captcha(self, tab_id: str = "default") -> Dict[str, Any]:
        page = self._pages.get(tab_id)
        if page is None:
            return {"error": "No active page"}
        ctype = await self._captcha.detect(page)
        return {"captcha_type": ctype.value, "detected": ctype.value != "none"}

    async def wait_for_captcha_solve(self, tab_id: str = "default",
                                      action: str = "") -> Dict[str, Any]:
        page = self._pages.get(tab_id)
        if page is None:
            return {"error": "No active page"}
        result = await self._captcha.check_and_handle(page, action)
        return result.to_dict()

    # ── Phase 8.2 — Anti-bot ──────────────────────────────────────────────────

    async def scroll(self, tab_id: str = "default", direction: str = "down",
                     amount: int = 3) -> Dict[str, Any]:
        page = self._pages.get(tab_id)
        if page is None:
            return {"success": False}
        await self._antibot.human_scroll(page, direction, amount)
        return {"success": True, "direction": direction, "amount": amount}

    async def human_type(self, selector: str, text: str,
                          tab_id: str = "default") -> Dict[str, Any]:
        page = self._pages.get(tab_id)
        if page is None:
            return {"success": False}
        ok = await self._antibot.human_type(page, selector, text)
        return {"success": ok}

    async def check_blocked(self, tab_id: str = "default") -> Dict[str, Any]:
        page = self._pages.get(tab_id)
        if page is None:
            return {"blocked": False}
        blocked, reason = await self._antibot.is_blocked(page)
        return {"blocked": blocked, "reason": reason}

    # ── Phase 8.3 — Smart selectors ───────────────────────────────────────────

    async def smart_find(self, selector: str, tab_id: str = "default",
                          fallbacks: Optional[List[str]] = None,
                          text: Optional[str] = None) -> Dict[str, Any]:
        page = self._pages.get(tab_id)
        if page is None:
            return {"found": False, "error": "No active page"}
        result = await self._selector.find(page, selector, fallbacks=fallbacks, text=text)
        return result.to_dict()

    # ── Phase 8.4 — Retry ────────────────────────────────────────────────────

    async def navigate_with_retry(self, url: str, tab_id: str = "default") -> Dict[str, Any]:
        page = await self._get_or_create_page(tab_id)
        if page is None:
            return {"success": False, "error": "Browser not available"}
        result = await self._retry.navigate_with_retry(page, url)
        return result.to_dict()

    # ── Phase 8.5 — Form automation ───────────────────────────────────────────

    async def smart_fill_form(self, data: Dict[str, str], tab_id: str = "default",
                               submit: bool = False,
                               submit_selector: Optional[str] = None) -> Dict[str, Any]:
        page = self._pages.get(tab_id)
        if page is None:
            return {"success": False, "error": "No active page"}
        from .form_automation import FormAutomator
        fa = FormAutomator(page)
        result = await fa.fill_and_submit(data, submit=submit, submit_selector=submit_selector)
        return result.to_dict()

    async def detect_forms(self, tab_id: str = "default") -> Dict[str, Any]:
        page = self._pages.get(tab_id)
        if page is None:
            return {"forms": []}
        from .form_automation import FormAutomator
        fa = FormAutomator(page)
        forms = await fa.detect_forms()
        return {"forms": forms, "count": len(forms)}

    async def get_validation_errors(self, tab_id: str = "default") -> Dict[str, Any]:
        page = self._pages.get(tab_id)
        if page is None:
            return {"errors": []}
        from .form_automation import FormAutomator
        fa = FormAutomator(page)
        errors = await fa.get_validation_errors()
        return {"errors": errors, "count": len(errors)}

    # ── Phase 8.6 — Sessions ─────────────────────────────────────────────────

    async def save_session(self, url: str, username: str = "") -> Dict[str, Any]:
        ok = await self._session_mgr.save_session(url, username)
        return {"saved": ok, "url": url}

    async def load_session(self, url: str) -> Dict[str, Any]:
        ok = await self._session_mgr.load_session(url)
        return {"loaded": ok, "url": url}

    async def check_session_expired(self, tab_id: str = "default") -> Dict[str, Any]:
        page = self._pages.get(tab_id)
        if page is None:
            return {"expired": True, "error": "No active page"}
        expired = await self._session_mgr.is_session_expired(page)
        return {"expired": expired, "url": page.url}

    def list_sessions(self) -> Dict[str, Any]:
        return {"sessions": self._session_mgr.list_sessions()}

    # ── Phase 8.7 — Downloads ─────────────────────────────────────────────────

    async def trigger_download(self, selector: str, tab_id: str = "default") -> Dict[str, Any]:
        page = self._pages.get(tab_id)
        if page is None:
            return {"success": False, "error": "No active page"}
        rec = await self._download_mgr.trigger_download(page, selector)
        return rec.to_dict() if rec else {"success": False}

    def list_downloads(self) -> Dict[str, Any]:
        return {"downloads": self._download_mgr.list_downloads()}

    # ── Phase 8.8 — Uploads ───────────────────────────────────────────────────

    async def upload_files(self, files: List[str], tab_id: str = "default",
                            selector: Optional[str] = None,
                            submit_after: bool = False,
                            confirm: bool = False) -> Dict[str, Any]:
        page = self._pages.get(tab_id)
        if page is None:
            return {"success": False, "error": "No active page"}
        rec = await self._upload_mgr.upload(
            page, files=files, selector=selector,
            confirm_before=confirm, submit_after=submit_after,
        )
        return rec.to_dict()

    def list_uploads(self) -> Dict[str, Any]:
        return {"uploads": self._upload_mgr.list_uploads()}

    # ── Phase 8.9 — Browser memory ────────────────────────────────────────────

    def browser_memory_summary(self) -> Dict[str, Any]:
        return self._memory.summary()

    def top_visited_sites(self, n: int = 10) -> Dict[str, Any]:
        return {"sites": self._memory.top_visited(n)}

    def recent_searches(self, n: int = 20) -> Dict[str, Any]:
        return {"searches": self._memory.recent_searches(n)}

    def save_current_tab(self, tab_id: str = "default") -> Dict[str, Any]:
        page = self._pages.get(tab_id)
        if page is None:
            return {"saved": False}
        # Synchronous because page.url is a property
        try:
            url = page.url
            self._memory.save_tab(tab_id, url, "", profile=self._profiles.active_id)
            self._memory.save()
            return {"saved": True, "tab_id": tab_id, "url": url}
        except Exception as exc:
            return {"saved": False, "error": str(exc)}

    def get_saved_tabs(self) -> Dict[str, Any]:
        return {"tabs": self._memory.list_saved_tabs()}

    # ── Phase 8.10 — Profiles ─────────────────────────────────────────────────

    def list_profiles(self) -> Dict[str, Any]:
        return {"profiles": self._profiles.list_profiles(),
                "active": self._profiles.active_id}

    def switch_profile(self, profile_id: str) -> Dict[str, Any]:
        try:
            profile = self._profiles.switch_to(profile_id)
            return {"switched": True, "profile": profile.to_dict()}
        except KeyError as exc:
            return {"switched": False, "error": str(exc)}

    def create_profile(self, name: str, profile_id: Optional[str] = None,
                        color: str = "#95A5A6", description: str = "") -> Dict[str, Any]:
        try:
            profile = self._profiles.create_profile(name, profile_id, color, description)
            return {"created": True, "profile": profile.to_dict()}
        except ValueError as exc:
            return {"created": False, "error": str(exc)}

    def get_profile_history(self, profile_id: Optional[str] = None, n: int = 50) -> Dict[str, Any]:
        pid = profile_id or self._profiles.active_id
        return {"profile_id": pid, "history": self._profiles.get_history(pid, n)}
