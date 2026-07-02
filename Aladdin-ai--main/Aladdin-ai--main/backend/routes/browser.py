"""Browser automation routes — Phase 6 + Phase 8."""
from __future__ import annotations
import logging
from typing import Any, Dict, List, Optional
from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from fastapi.responses import Response
from pydantic import BaseModel
import base64

log = logging.getLogger(__name__)
router = APIRouter(prefix="/browser", tags=["Browser"])


# ── Request models ────────────────────────────────────────────────────────────

class NavigateRequest(BaseModel):
    url: str
    tab_id: str = "default"
    wait_for: str = "networkidle"
    anti_bot: bool = True
    handle_captcha: bool = True
    retry: bool = True


class ClickRequest(BaseModel):
    selector: str
    tab_id: str = "default"
    human_like: bool = True


class FillRequest(BaseModel):
    fields: Dict[str, str]
    tab_id: str = "default"
    submit_selector: Optional[str] = None


class SmartFillRequest(BaseModel):
    data: Dict[str, str]
    tab_id: str = "default"
    submit: bool = False
    submit_selector: Optional[str] = None


class LoginRequest(BaseModel):
    url: str
    username: str
    password: str
    username_selector: str = "input[type='email'],input[type='text'],input[name='username'],input[name='email']"
    password_selector: str = "input[type='password']"
    submit_selector: str = "button[type='submit'],input[type='submit']"
    success_selector: Optional[str] = None
    save_session: bool = True


class HumanTypeRequest(BaseModel):
    selector: str
    text: str
    tab_id: str = "default"


class SmartFindRequest(BaseModel):
    selector: str
    tab_id: str = "default"
    fallbacks: Optional[List[str]] = None
    text: Optional[str] = None


class UploadRequest(BaseModel):
    files: List[str]
    tab_id: str = "default"
    selector: Optional[str] = None
    submit_after: bool = False
    confirm: bool = False


class ProfileCreateRequest(BaseModel):
    name: str
    profile_id: Optional[str] = None
    color: str = "#95A5A6"
    description: str = ""


class ProfileSwitchRequest(BaseModel):
    profile_id: str


class SessionRequest(BaseModel):
    url: str
    username: str = ""


# ── Dependency ────────────────────────────────────────────────────────────────

def _get_browser(app_state):
    bc = app_state.get("browser")
    if not bc:
        raise HTTPException(503, "Browser controller not initialized")
    if not bc.available:
        raise HTTPException(503,
            "Playwright not installed. Run: pip install playwright && playwright install chromium")
    return bc


# ── Phase 6 routes ────────────────────────────────────────────────────────────

@router.post("/navigate")
async def navigate(req: NavigateRequest):
    """Navigate to URL with anti-bot, CAPTCHA, and retry support."""
    from ..main import app_state
    bc = _get_browser(app_state)
    result = await bc.navigate(req.url, req.tab_id, req.wait_for,
                                anti_bot=req.anti_bot,
                                handle_captcha=req.handle_captcha,
                                retry=req.retry)
    if not result.get("success"):
        raise HTTPException(502, result.get("error", "Navigation failed"))
    return result


@router.post("/click")
async def click(req: ClickRequest):
    """Click an element (human-like by default)."""
    from ..main import app_state
    bc = _get_browser(app_state)
    return await bc.click(req.selector, req.tab_id, req.human_like)


@router.post("/fill")
async def fill_form(req: FillRequest):
    from ..main import app_state
    bc = _get_browser(app_state)
    return await bc.fill_form(req.fields, req.tab_id, req.submit_selector)


@router.post("/screenshot")
async def take_screenshot(tab_id: str = "default", full_page: bool = True,
                           selector: Optional[str] = None, as_image: bool = False):
    from ..main import app_state
    bc = _get_browser(app_state)
    result = await bc.screenshot(tab_id, full_page, selector)
    if not result.get("success"):
        raise HTTPException(502, result.get("error", "Screenshot failed"))
    if as_image:
        png_data = base64.b64decode(result["image_base64"])
        return Response(content=png_data, media_type="image/png")
    return result


@router.post("/login")
async def login(req: LoginRequest):
    """Login with session persistence and MFA support."""
    from ..main import app_state
    bc = _get_browser(app_state)
    result = await bc.login(
        req.url, req.username, req.password,
        username_selector=req.username_selector,
        password_selector=req.password_selector,
        submit_selector=req.submit_selector,
        success_selector=req.success_selector,
        save_session=req.save_session,
    )
    return result


@router.get("/read")
async def read_page(tab_id: str = "default", extract_text: bool = True):
    from ..main import app_state
    bc = _get_browser(app_state)
    result = await bc.get_content(tab_id, extract_text)
    if not result.get("success"):
        raise HTTPException(502, result.get("error", "Could not read page"))
    return result


@router.get("/html")
async def get_html(tab_id: str = "default"):
    from ..main import app_state
    bc = _get_browser(app_state)
    return await bc.get_content(tab_id, extract_text=False)


@router.get("/tabs")
async def list_tabs():
    from ..main import app_state
    bc = app_state.get("browser")
    return {"tabs": bc.list_tabs() if bc else []}


@router.delete("/tab/{tab_id}")
async def close_tab(tab_id: str):
    from ..main import app_state
    bc = app_state.get("browser")
    if bc:
        await bc.close_tab(tab_id)
    return {"closed": tab_id}


# ── Phase 8.1 — CAPTCHA ───────────────────────────────────────────────────────

@router.get("/captcha/detect")
async def detect_captcha(tab_id: str = "default"):
    """Check if a CAPTCHA is present on the active page."""
    from ..main import app_state
    bc = _get_browser(app_state)
    return await bc.check_captcha(tab_id)


@router.post("/captcha/wait-solve")
async def wait_captcha_solve(tab_id: str = "default", action: str = ""):
    """Notify user about CAPTCHA and wait for them to solve it."""
    from ..main import app_state
    bc = _get_browser(app_state)
    return await bc.wait_for_captcha_solve(tab_id, action)


# ── Phase 8.2 — Anti-bot ─────────────────────────────────────────────────────

@router.post("/scroll")
async def scroll_page(tab_id: str = "default", direction: str = "down", amount: int = 3):
    """Scroll page in human-like fashion."""
    from ..main import app_state
    bc = _get_browser(app_state)
    return await bc.scroll(tab_id, direction, amount)


@router.post("/type")
async def human_type(req: HumanTypeRequest):
    """Type text into an element with realistic per-character delays."""
    from ..main import app_state
    bc = _get_browser(app_state)
    return await bc.human_type(req.selector, req.text, req.tab_id)


@router.get("/blocked")
async def check_blocked(tab_id: str = "default"):
    """Check if the current page is a bot-block / rate-limit page."""
    from ..main import app_state
    bc = _get_browser(app_state)
    return await bc.check_blocked(tab_id)


# ── Phase 8.3 — Smart selectors ──────────────────────────────────────────────

@router.post("/find")
async def smart_find(req: SmartFindRequest):
    """Find element using multi-strategy smart selector."""
    from ..main import app_state
    bc = _get_browser(app_state)
    return await bc.smart_find(req.selector, req.tab_id, req.fallbacks, req.text)


# ── Phase 8.4 — Retry ────────────────────────────────────────────────────────

@router.post("/navigate/retry")
async def navigate_retry(req: NavigateRequest):
    """Navigate with explicit retry logic and backoff."""
    from ..main import app_state
    bc = _get_browser(app_state)
    return await bc.navigate_with_retry(req.url, req.tab_id)


# ── Phase 8.5 — Form automation ──────────────────────────────────────────────

@router.post("/form/fill")
async def smart_fill_form(req: SmartFillRequest):
    """Intelligently fill form fields and optionally submit."""
    from ..main import app_state
    bc = _get_browser(app_state)
    return await bc.smart_fill_form(req.data, req.tab_id, req.submit, req.submit_selector)


@router.get("/form/detect")
async def detect_forms(tab_id: str = "default"):
    """Detect all forms on the current page."""
    from ..main import app_state
    bc = _get_browser(app_state)
    return await bc.detect_forms(tab_id)


@router.get("/form/errors")
async def get_form_errors(tab_id: str = "default"):
    """Get visible validation errors on the page."""
    from ..main import app_state
    bc = _get_browser(app_state)
    return await bc.get_validation_errors(tab_id)


# ── Phase 8.6 — Sessions ─────────────────────────────────────────────────────

@router.post("/session/save")
async def save_session(req: SessionRequest):
    """Save current browser session for a site."""
    from ..main import app_state
    bc = _get_browser(app_state)
    return await bc.save_session(req.url, req.username)


@router.post("/session/load")
async def load_session(req: SessionRequest):
    """Load a previously saved session."""
    from ..main import app_state
    bc = _get_browser(app_state)
    return await bc.load_session(req.url)


@router.get("/session/check")
async def check_session(tab_id: str = "default"):
    """Check if the current session has expired."""
    from ..main import app_state
    bc = _get_browser(app_state)
    return await bc.check_session_expired(tab_id)


@router.get("/sessions")
async def list_sessions():
    """List all saved sessions."""
    from ..main import app_state
    bc = _get_browser(app_state)
    return bc.list_sessions()


# ── Phase 8.7 — Downloads ─────────────────────────────────────────────────────

@router.post("/download/trigger")
async def trigger_download(selector: str, tab_id: str = "default"):
    """Click a selector and capture the resulting download."""
    from ..main import app_state
    bc = _get_browser(app_state)
    return await bc.trigger_download(selector, tab_id)


@router.get("/downloads")
async def list_downloads():
    """List all tracked downloads."""
    from ..main import app_state
    bc = _get_browser(app_state)
    return bc.list_downloads()


# ── Phase 8.8 — Uploads ───────────────────────────────────────────────────────

@router.post("/upload")
async def upload_files(req: UploadRequest):
    """Upload files to the current page's file input."""
    from ..main import app_state
    bc = _get_browser(app_state)
    return await bc.upload_files(
        req.files, req.tab_id, req.selector, req.submit_after, req.confirm
    )


@router.get("/uploads")
async def list_uploads():
    """List all tracked uploads."""
    from ..main import app_state
    bc = _get_browser(app_state)
    return bc.list_uploads()


# ── Phase 8.9 — Browser memory ────────────────────────────────────────────────

@router.get("/memory/summary")
async def memory_summary():
    """Summary of browser memory: visits, searches, tasks."""
    from ..main import app_state
    bc = _get_browser(app_state)
    return bc.browser_memory_summary()


@router.get("/memory/top-sites")
async def top_sites(n: int = 10):
    """Most frequently visited sites."""
    from ..main import app_state
    bc = _get_browser(app_state)
    return bc.top_visited_sites(n)


@router.get("/memory/searches")
async def recent_searches(n: int = 20):
    """Recent search queries."""
    from ..main import app_state
    bc = _get_browser(app_state)
    return bc.recent_searches(n)


@router.post("/memory/save-tab")
async def save_tab(tab_id: str = "default"):
    """Save current tab to memory."""
    from ..main import app_state
    bc = _get_browser(app_state)
    return bc.save_current_tab(tab_id)


@router.get("/memory/saved-tabs")
async def saved_tabs():
    """List all saved tabs."""
    from ..main import app_state
    bc = _get_browser(app_state)
    return bc.get_saved_tabs()


# ── Phase 8.10 — Profiles ─────────────────────────────────────────────────────

@router.get("/profiles")
async def list_profiles():
    """List all browser profiles."""
    from ..main import app_state
    bc = _get_browser(app_state)
    return bc.list_profiles()


@router.post("/profiles/create")
async def create_profile(req: ProfileCreateRequest):
    """Create a new browser profile."""
    from ..main import app_state
    bc = _get_browser(app_state)
    return bc.create_profile(req.name, req.profile_id, req.color, req.description)


@router.post("/profiles/switch")
async def switch_profile(req: ProfileSwitchRequest):
    """Switch to a different browser profile."""
    from ..main import app_state
    bc = _get_browser(app_state)
    return bc.switch_profile(req.profile_id)


@router.get("/profiles/{profile_id}/history")
async def profile_history(profile_id: str, n: int = 50):
    """Get browsing history for a specific profile."""
    from ..main import app_state
    bc = _get_browser(app_state)
    return bc.get_profile_history(profile_id, n)
