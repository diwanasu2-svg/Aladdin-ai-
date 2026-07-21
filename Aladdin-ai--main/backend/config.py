"""Configuration management for Aladdin AI Backend."""

from __future__ import annotations

import os
import logging
from pathlib import Path
from typing import Any, Dict, List, Optional

import yaml
from dotenv import load_dotenv

log = logging.getLogger(__name__)

# Load .env from multiple candidate paths
_ROOT = Path(__file__).resolve().parent.parent
for _env_path in [_ROOT / ".env", Path(".env"), Path(__file__).parent / ".env"]:
    if _env_path.exists():
        load_dotenv(_env_path)
        break


def _find_config_yaml() -> Optional[Path]:
    """Search for config.yaml in known locations."""
    candidates = [
        _ROOT / "config.yaml",
        _ROOT / "Aladdin-ai--main" / "config.yaml",
        Path("config.yaml"),
        Path(__file__).parent.parent / "config.yaml",
    ]
    for c in candidates:
        if c.exists():
            return c
    return None


def _load_yaml(path: Path) -> Dict[str, Any]:
    try:
        with open(path, encoding="utf-8") as f:
            return yaml.safe_load(f) or {}
    except Exception as exc:
        log.warning("Could not load %s: %s", path, exc)
        return {}


class BackendConfig:
    """Central configuration object for the backend."""

    def __init__(self) -> None:
        yaml_path = _find_config_yaml()
        self._yaml: Dict[str, Any] = _load_yaml(yaml_path) if yaml_path else {}
        if yaml_path:
            log.info("Loaded config from %s", yaml_path)
        else:
            log.warning("config.yaml not found — using defaults + env vars")

    # ── Provider keys ─────────────────────────────────────────────────────────
    @property
    def openai_api_key(self) -> Optional[str]:
        return os.getenv("OPENAI_API_KEY") or self._yaml.get("openai", {}).get("api_key")

    @property
    def gemini_api_key(self) -> Optional[str]:
        return os.getenv("GEMINI_API_KEY") or self._yaml.get("gemini", {}).get("api_key")

    @property
    def anthropic_api_key(self) -> Optional[str]:
        return os.getenv("ANTHROPIC_API_KEY") or self._yaml.get("anthropic", {}).get("api_key")

    # ── Default provider / model ──────────────────────────────────────────────
    @property
    def default_provider(self) -> str:
        return os.getenv("DEFAULT_LLM_PROVIDER") or self._yaml.get("llm", {}).get("provider", "ollama")

    @property
    def default_model(self) -> str:
        return os.getenv("DEFAULT_LLM_MODEL") or self._yaml.get("llm", {}).get("model", "llama3.1")

    @property
    def ollama_host(self) -> str:
        return os.getenv("OLLAMA_HOST") or self._yaml.get("ollama", {}).get("host", "http://localhost:11434")

    # ── Memory ────────────────────────────────────────────────────────────────
    @property
    def memory_db_path(self) -> Path:
        raw = self._yaml.get("memory", {}).get("db_path", "data/aladdin_memory.sqlite")
        p = Path(raw)
        return p if p.is_absolute() else _ROOT / p

    @property
    def data_dir(self) -> Path:
        d = self.memory_db_path.parent
        d.mkdir(parents=True, exist_ok=True)
        return d

    # ── Voice ─────────────────────────────────────────────────────────────────
    @property
    def whisper_model(self) -> str:
        return os.getenv("WHISPER_MODEL") or self._yaml.get("whisper", {}).get("model", "base")

    @property
    def whisper_device(self) -> str:
        return os.getenv("WHISPER_DEVICE") or self._yaml.get("whisper", {}).get("device", "cpu")

    # ── Session ───────────────────────────────────────────────────────────────
    @property
    def session_timeout_seconds(self) -> int:
        return int(os.getenv("SESSION_TIMEOUT", "3600"))

    @property
    def max_context_tokens(self) -> int:
        return int(os.getenv("MAX_CONTEXT_TOKENS", "4096"))

    # ── Retry ─────────────────────────────────────────────────────────────────
    @property
    def max_retries(self) -> int:
        return int(os.getenv("MAX_RETRIES", "3"))


# Singleton
config = BackendConfig()
