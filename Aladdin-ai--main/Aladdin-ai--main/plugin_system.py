"""Plugin system — load aladdin_core/plugins/*.py dynamically."""

from __future__ import annotations

import importlib.util
import logging
from pathlib import Path
from typing import Any, Dict, List, Optional, TYPE_CHECKING

from .config import PluginCfg

if TYPE_CHECKING:
    from .llm import OllamaClient
    from .memory import ConversationMemory

log = logging.getLogger(__name__)


class Plugin:
    """Base class for Aladdin plugins."""

    name: str = "base_plugin"
    description: str = ""

    def on_load(self, context: Dict[str, Any]) -> None:
        """Called when plugin is loaded. context contains llm, memory, etc."""

    def on_user_input(self, text: str) -> Optional[str]:
        """
        Called before LLM. Return a string to short-circuit the LLM,
        or None to let normal processing continue.
        """
        return None

    def on_reply(self, user_text: str, reply: str) -> str:
        """Called after LLM reply. May transform the reply."""
        return reply

    def on_shutdown(self) -> None:
        """Called on graceful shutdown."""


class PluginManager:
    """Loads and manages plugins."""

    def __init__(self, cfg: PluginCfg):
        self.cfg = cfg
        self._plugins: List[Plugin] = []
        self._context: Dict[str, Any] = {}

    def set_context(self, **kwargs: Any) -> None:
        self._context.update(kwargs)

    def load_all(self) -> None:
        plugin_dir = Path(self.cfg.plugin_dir)
        if not plugin_dir.exists():
            return
        for py_file in sorted(plugin_dir.glob("*.py")):
            if py_file.stem.startswith("_"):
                continue
            if self.cfg.enabled and py_file.stem not in self.cfg.enabled:
                continue
            self._load_plugin(py_file)

    def _load_plugin(self, path: Path) -> None:
        try:
            spec = importlib.util.spec_from_file_location(path.stem, path)
            mod = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(mod)
            for attr in dir(mod):
                obj = getattr(mod, attr)
                if (
                    isinstance(obj, type)
                    and issubclass(obj, Plugin)
                    and obj is not Plugin
                ):
                    instance = obj()
                    instance.on_load(self._context)
                    self._plugins.append(instance)
                    log.info("Loaded plugin: %s", instance.name)
        except Exception as exc:
            log.error("Failed to load plugin %s: %s", path.stem, exc)

    def process_input(self, text: str) -> Optional[str]:
        for plugin in self._plugins:
            try:
                result = plugin.on_user_input(text)
                if result is not None:
                    return result
            except Exception as exc:
                log.error("Plugin %s input error: %s", plugin.name, exc)
        return None

    def process_reply(self, user_text: str, reply: str) -> str:
        for plugin in self._plugins:
            try:
                reply = plugin.on_reply(user_text, reply)
            except Exception as exc:
                log.error("Plugin %s reply error: %s", plugin.name, exc)
        return reply

    def shutdown(self) -> None:
        for plugin in self._plugins:
            try:
                plugin.on_shutdown()
            except Exception:
                pass
