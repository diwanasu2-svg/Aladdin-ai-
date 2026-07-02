"""Telegram bot integration for Aladdin."""

from __future__ import annotations

import logging
import threading
from typing import TYPE_CHECKING, Callable, Optional

from .config import TelegramCfg

if TYPE_CHECKING:
    from .llm import OllamaClient
    from .memory import ConversationMemory

log = logging.getLogger(__name__)


class TelegramBot:
    """
    Telegram interface for Aladdin.
    Requires: pip install python-telegram-bot>=20.0
    Set bot_token in config.yaml under telegram: bot_token: YOUR_TOKEN
    """

    def __init__(
        self,
        cfg: TelegramCfg,
        llm: "OllamaClient",
        memory: "ConversationMemory",
        tts_callback: Optional[Callable[[str], str]] = None,
    ):
        self.cfg = cfg
        self.llm = llm
        self.memory = memory
        self.tts_callback = tts_callback
        self._app = None

    def start(self) -> None:
        """Start the Telegram bot in a background thread."""
        if not self.cfg.enabled or not self.cfg.bot_token:
            log.info("Telegram bot disabled (no token configured).")
            return
        try:
            from telegram.ext import (
                Application,
                CommandHandler,
                MessageHandler,
                filters,
            )
        except ImportError:
            log.warning(
                "python-telegram-bot not installed. Run: pip install python-telegram-bot"
            )
            return

        thread = threading.Thread(target=self._run, daemon=True, name="telegram-bot")
        thread.start()
        log.info("Telegram bot started.")

    def _run(self) -> None:
        import asyncio
        from telegram.ext import Application, CommandHandler, MessageHandler, filters
        from telegram import Update
        from telegram.ext import ContextTypes

        async def start_cmd(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
            await update.message.reply_text(
                "👋 I'm Aladdin, your AI assistant! Send me a message."
            )

        async def handle_message(
            update: Update, ctx: ContextTypes.DEFAULT_TYPE
        ) -> None:
            user_id = update.effective_user.id
            if self.cfg.allowed_users and user_id not in self.cfg.allowed_users:
                await update.message.reply_text("Sorry, you're not authorized.")
                return

            text = update.message.text or ""
            history = self.memory.recent(8)
            reply = self.llm.chat(text, history=history)
            self.memory.append(text, reply)
            await update.message.reply_text(reply)

        app = Application.builder().token(self.cfg.bot_token).build()
        app.add_handler(CommandHandler("start", start_cmd))
        app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_message))
        app.run_polling(drop_pending_updates=True)

    def stop(self) -> None:
        if self._app:
            try:
                self._app.stop()
            except Exception:
                pass
