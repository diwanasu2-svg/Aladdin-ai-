"""Browser automation subsystem — Phase 6 + Phase 8."""
from .controller import BrowserController

# Phase 8 modules
from .captcha import CaptchaHandler, CaptchaType
from .antibot import AntiBotEvasion
from .selectors import SmartSelector
from .retry import RetryManager
from .form_automation import FormAutomator
from .session_manager import SessionManager
from .download_manager import DownloadManager
from .upload_manager import UploadManager
from .browser_memory import BrowserMemory
from .profiles import ProfileManager

__all__ = [
    "BrowserController",
    # Phase 8
    "CaptchaHandler", "CaptchaType",
    "AntiBotEvasion",
    "SmartSelector",
    "RetryManager",
    "FormAutomator",
    "SessionManager",
    "DownloadManager",
    "UploadManager",
    "BrowserMemory",
    "ProfileManager",
]
