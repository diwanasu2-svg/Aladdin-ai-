"""
Aladdin AI Backend v2.3 — FastAPI Server
==========================================
Core AI Brain · Memory · Voice · Vision · Wake Word
Tool Calling · Browser Automation · File Handling
Calendar · Reminders · Web Search

Tasks 3, 7: Auth router registered; CORS locked to specific origins.
Tasks 2: Security ASGI middlewares added.
Task 32: Static folder warning added.
"""
from __future__ import annotations
import asyncio
import logging
import os
from contextlib import asynccontextmanager
from typing import Any, Dict
from pathlib import Path

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

from .config import config
from .llm.session_manager import session_manager
from .memory.short_term import ShortTermMemory
from .memory.long_term import LongTermMemory
from .memory.semantic import SemanticMemory
from .memory.contacts import ContactsMemory
from .memory.profile import ProfileMemory
from .memory.preferences import PreferencesMemory
from .memory.projects import ProjectsMemory
from .memory.locations import LocationsMemory
from .voice.stt import SpeechToText
from .voice.vad import VoiceActivityDetector

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s — %(message)s")
log = logging.getLogger(__name__)

app_state: Dict[str, Any] = {}


def _init_llm_providers():
    if config.openai_api_key:
        try:
            from .llm.openai_client import OpenAIClient
            app_state["openai"] = OpenAIClient(config.openai_api_key)
            log.info("OpenAI LLM ready")
        except ImportError as exc:
            log.warning("OpenAI LLM import failed (missing dependency?): %s", exc)
        except Exception as exc:
            log.warning("OpenAI LLM init failed: %s", exc)
    if config.gemini_api_key:
        try:
            from .llm.gemini_client import GeminiClient
            app_state["gemini"] = GeminiClient(config.gemini_api_key)
            log.info("Gemini LLM ready")
        except ImportError as exc:
            log.warning("Gemini LLM import failed (missing dependency?): %s", exc)
        except Exception as exc:
            log.warning("Gemini LLM init failed: %s", exc)
    if config.anthropic_api_key:
        try:
            from .llm.anthropic_client import AnthropicClient
            app_state["anthropic"] = AnthropicClient(config.anthropic_api_key)
            log.info("Anthropic LLM ready")
        except ImportError as exc:
            log.warning("Anthropic LLM import failed (missing dependency?): %s", exc)
        except Exception as exc:
            log.warning("Anthropic LLM init failed: %s", exc)
    try:
        from .llm.ollama_client import OllamaClient
        app_state["ollama"] = OllamaClient(config.ollama_host)
        log.info("Ollama LLM ready")
    except ImportError as exc:
        log.warning("Ollama import failed (missing dependency?): %s", exc)
    except Exception as exc:
        log.warning("Ollama: %s", exc)
    app_state["default_provider"] = config.default_provider
    app_state["default_model"] = config.default_model
    app_state["default_system_prompt"] = (
        "You are Aladdin, a helpful, concise, and friendly AI assistant. "
        "Reply in short, clear sentences. Always reply in the same language the user uses."
    )


def _init_memory():
    data = config.data_dir
    for name, cls, path in [
        ("short_term", ShortTermMemory, "short_term.sqlite"),
        ("long_term", LongTermMemory, "long_term.sqlite"),
        ("semantic", SemanticMemory, "semantic.sqlite"),
        ("contacts", ContactsMemory, "contacts.sqlite"),
        ("profile", ProfileMemory, "profile.sqlite"),
        ("preferences", PreferencesMemory, "preferences.sqlite"),
        ("projects", ProjectsMemory, "projects.sqlite"),
        ("locations", LocationsMemory, "locations.sqlite"),
    ]:
        try:
            app_state[name] = cls(data / path)
        except ImportError as exc:
            log.warning("%s import failed (missing dependency?): %s", name, exc)
        except Exception as exc:
            log.warning("%s init failed: %s", name, exc)
    log.info("Memory ready")


def _init_voice():
    try:
        app_state["stt"] = SpeechToText(model_name=config.whisper_model, device=config.whisper_device)
        log.info("STT ready")
    except ImportError as exc:
        log.warning("STT import failed (missing dependency?): %s", exc)
    except Exception as exc:
        log.warning("STT init failed: %s", exc)
    try:
        app_state["vad"] = VoiceActivityDetector()
        log.info("VAD ready")
    except ImportError as exc:
        log.warning("VAD import failed (missing dependency?): %s", exc)
    except Exception as exc:
        log.warning("VAD init failed: %s", exc)


def _init_tts():
    from .tts.manager import TTSManager
    tts = TTSManager()
    if config.openai_api_key:
        try:
            from .tts.openai_tts import OpenAITTSClient
            tts.register("openai", OpenAITTSClient(config.openai_api_key))
        except ImportError as exc:
            log.warning("OpenAI TTS import failed: %s", exc)
        except Exception as exc:
            log.warning("OpenAI TTS: %s", exc)
    el_key = os.getenv("ELEVENLABS_API_KEY")
    if el_key:
        try:
            from .tts.elevenlabs_tts import ElevenLabsTTSClient
            tts.register("elevenlabs", ElevenLabsTTSClient(el_key))
        except ImportError as exc:
            log.warning("ElevenLabs TTS import failed: %s", exc)
        except Exception as exc:
            log.warning("ElevenLabs TTS: %s", exc)
    piper_model = os.getenv("PIPER_MODEL_PATH", "")
    if piper_model and Path(piper_model).exists():
        try:
            from .tts.piper_tts import PiperTTSClient
            tts.register("piper", PiperTTSClient(
                piper_binary=os.getenv("PIPER_BINARY", "piper"), model_path=piper_model))
        except ImportError as exc:
            log.warning("Piper TTS import failed: %s", exc)
        except Exception as exc:
            log.warning("Piper TTS: %s", exc)
    from .tts.browser_tts import BrowserTTSClient
    tts.register("browser", BrowserTTSClient())
    app_state["tts"] = tts
    log.info("TTS ready: %s", tts.available_providers)


def _init_wake():
    from .wake.manager import WakeManager
    wake = WakeManager()
    oww_models = [m.strip() for m in os.getenv("OWW_MODEL_PATHS", "").split(",") if m.strip()]
    wake.setup_openwakeword(model_paths=oww_models or None)
    pk = os.getenv("PICOVOICE_ACCESS_KEY", "")
    if pk:
        wake.setup_porcupine(access_key=pk)
    wake_words = [w.strip() for w in os.getenv("WAKE_WORDS", "aladdin,hey aladdin").split(",")]
    wake.setup_browser(wake_words=wake_words)
    app_state["wake"] = wake
    log.info("Wake word ready")


def _init_vision():
    from .vision.manager import VisionManager
    vm = VisionManager()
    vm.setup(openai_key=config.openai_api_key, gemini_key=config.gemini_api_key)
    app_state["vision"] = vm
    log.info("Vision ready: %s", vm.capabilities)


def _init_tools():
    from .tools.manager import ToolManager
    from .tools.weather import WeatherTool
    from .tools.browser import WebSearchTool, ReadPageTool
    from .tools.email import SendEmailTool, ReadEmailsTool
    from .tools.files import ReadFileTool, WriteFileTool, ListFilesTool, DeleteFileTool
    from .tools import reminder as rem_mod
    from .tools import calendar as cal_mod
    from .tools import notes as notes_mod
    from .tools import contacts as contacts_mod
    from .tools.reminder import CreateReminderTool, ListRemindersTool, DeleteReminderTool, UpdateReminderTool
    from .tools.calendar import CreateEventTool, ListEventsTool, DeleteEventTool, UpdateEventTool
    from .tools.notes import CreateNoteTool, ListNotesTool, UpdateNoteTool, DeleteNoteTool
    from .tools.contacts import AddContactTool, ListContactsTool, SearchContactsTool, DeleteContactTool
    from .tools.files import init as files_init

    data = config.data_dir
    rem_mod.init_db(data / "reminders_tool.sqlite")
    cal_mod.init_db(data / "calendar_tool.sqlite")
    notes_mod.init_db(data / "notes.sqlite")
    contacts_mod.init(app_state.get("contacts"))
    files_init(data / "workspace_files")

    tm = ToolManager()
    tm.register_all([
        WeatherTool(), WebSearchTool(), ReadPageTool(),
        SendEmailTool(), ReadEmailsTool(),
        CreateReminderTool(), ListRemindersTool(), DeleteReminderTool(), UpdateReminderTool(),
        CreateEventTool(), ListEventsTool(), DeleteEventTool(), UpdateEventTool(),
        CreateNoteTool(), ListNotesTool(), UpdateNoteTool(), DeleteNoteTool(),
        AddContactTool(), ListContactsTool(), SearchContactsTool(), DeleteContactTool(),
        ReadFileTool(), WriteFileTool(), ListFilesTool(), DeleteFileTool(),
    ])
    app_state["tool_manager"] = tm
    log.info("Tools ready: %d tools", len(tm.list_tools()))


def _init_browser():
    try:
        from .browser.controller import BrowserController
        bc = BrowserController()
        app_state["browser"] = bc
        log.info("Browser controller ready (playwright=%s)", bc.available)
    except ImportError as exc:
        log.warning("Browser import failed (playwright not installed?): %s", exc)
    except Exception as exc:
        log.warning("Browser init failed: %s", exc)


def _init_files():
    from .files.upload import FileStore
    from .files.summarizer import FileSummarizer
    from .files.qa import FileQA
    data = config.data_dir
    store = FileStore(data / "uploads")
    app_state["file_store"] = store
    llm = (app_state.get("openai") or app_state.get("gemini") or
           app_state.get("anthropic") or app_state.get("ollama"))
    app_state["file_summarizer"] = FileSummarizer(llm)
    app_state["file_qa"] = FileQA(llm)
    log.info("File handling ready")


def _init_calendar():
    from .calendar.endpoints import CalendarStore
    from .calendar.google_calendar import GoogleCalendarSync
    data = config.data_dir
    store = CalendarStore(data / "calendar.sqlite")
    app_state["calendar_store"] = store
    gc = GoogleCalendarSync(local_store=store)
    app_state["google_calendar"] = gc
    log.info("Calendar ready (google_sync=%s)", gc.available)


def _init_reminders():
    from .reminders.manager import ReminderManager
    data = config.data_dir
    mgr = ReminderManager(data / "reminders.sqlite")
    app_state["reminder_manager"] = mgr
    log.info("Reminders ready")


def _init_search():
    from .search.api import SearchEngine
    engine = SearchEngine()
    app_state["search_engine"] = engine
    log.info("Search engine ready (brave=%s, google=%s, news=%s)",
             engine.brave.available, engine.google.available, engine.news.available)


@asynccontextmanager
async def lifespan(app: FastAPI):
    log.info("=== Aladdin AI Backend v2.3 starting ===")

    # Task 39: Verify database schemas and auto-repair on startup
    try:
        from .db.schema_verification import run_startup_schema_check
        run_startup_schema_check(config.data_dir)
    except Exception as _exc:
        log.warning("Schema check skipped (non-fatal): %s", _exc)

    _init_llm_providers()
    _init_memory()
    _init_voice()
    _init_tts()
    _init_wake()
    _init_vision()
    _init_tools()
    _init_browser()
    _init_files()
    _init_calendar()
    _init_reminders()
    _init_search()
    from .reminders.notifications import check_due_reminders
    task = asyncio.create_task(check_due_reminders(app_state["reminder_manager"]))
    log.info("=== Startup complete ===")
    yield
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        pass
    session_manager.evict_expired()
    bc = app_state.get("browser")
    if bc:
        try:
            await bc.close()
        except Exception as exc:
            log.warning("Browser close error: %s", exc)
    log.info("=== Shutdown complete ===")


app = FastAPI(
    title="Aladdin AI Backend",
    version="2.3.0",
    description=(
        "Core AI Brain · Memory · Voice · Vision · Wake Word · "
        "Tools · Browser · Files · Calendar · Reminders · Search"
    ),
    lifespan=lifespan,
)

# ── Task 7: CORS — specific origins, not wildcard ─────────────────────────────
_ALLOWED_ORIGINS = [
    o.strip()
    for o in os.getenv(
        "CORS_ALLOWED_ORIGINS",
        "http://localhost:3000,http://localhost:8080,http://127.0.0.1:3000",
    ).split(",")
    if o.strip()
]
app.add_middleware(
    CORSMiddleware,
    allow_origins=_ALLOWED_ORIGINS,
    allow_credentials=True,
    allow_methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type", "Accept", "X-Requested-With"],
)
log.info("CORS allowed origins: %s", _ALLOWED_ORIGINS)

# ── Task 2: Security ASGI middlewares ─────────────────────────────────────────
from .security.https_enforcement import HTTPSEnforcerMiddleware
from .security.rate_limiter import RateLimitMiddleware

app.add_middleware(HTTPSEnforcerMiddleware)
app.add_middleware(RateLimitMiddleware)

# ── Task 32: Static folder — warn if missing ──────────────────────────────────
_STATIC = Path(__file__).parent.parent / "frontend" / "static"
if _STATIC.exists():
    app.mount("/static", StaticFiles(directory=str(_STATIC)), name="static")
    log.info("Static files mounted from: %s", _STATIC)
else:
    log.warning("Static folder not found at %s — /static will not be served", _STATIC)

# ── Routers ───────────────────────────────────────────────────────────────────
from .routes.health    import router as health_router
from .routes.chat      import router as chat_router
from .routes.memory    import router as memory_router
from .routes.voice     import router as voice_router
from .routes.tts       import router as tts_router
from .routes.wake      import router as wake_router
from .routes.vision    import router as vision_router
from .routes.tools     import router as tools_router
from .routes.browser   import router as browser_router
from .routes.files     import router as files_router
from .routes.calendar  import router as calendar_router
from .routes.reminders import router as reminders_router
from .routes.search    import router as search_router

# Task 3: Auth router properly registered (no silent try/except skip)
from .routes.auth_routes import router as auth_router

try:
    from .routes.intelligence import router as intelligence_router
except ImportError as exc:
    intelligence_router = None
    log.warning("Intelligence routes not available: %s", exc)

try:
    from .routes.diagnostics import router as diagnostics_router
except ImportError as exc:
    diagnostics_router = None
    log.warning("Diagnostics routes not available: %s", exc)

from .websocket.listening import router as ws_router

_routers = [
    health_router, chat_router, memory_router, voice_router,
    tts_router, wake_router, vision_router, tools_router,
    browser_router, files_router, calendar_router,
    reminders_router, search_router, ws_router,
    auth_router,  # Task 3: always registered
]
_optional = [intelligence_router, diagnostics_router]

for r in _routers + [r for r in _optional if r is not None]:
    app.include_router(r)


@app.get("/")
async def root():
    return {
        "message": "Aladdin AI Backend v2.3.0",
        "docs": "/docs",
        "health": "/health",
        "websocket": "/ws/listen",
        "auth": "/api/auth",
        "features": [
            "Core AI (OpenAI/Gemini/Anthropic/Ollama)",
            "Memory (short/long-term/semantic/contacts)",
            "Voice (STT/VAD/TTS/Wake Word)",
            "Vision (GPT-4o/Gemini/OCR/PDF)",
            "Tools (weather/email/reminders/calendar/notes/files)",
            "Browser Automation (Playwright)",
            "File Handling (PDF/DOCX/Excel/OCR)",
            "Calendar (CRUD/recurring/Google Sync/conflicts)",
            "Reminders (CRUD/snooze/recurring/notifications)",
            "Web Search (Brave/Google CSE/NewsAPI/DuckDuckGo)",
        ],
    }
