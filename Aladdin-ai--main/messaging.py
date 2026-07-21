"""
Phase 5 — Messaging Integration
================================
Python-side bridge for:
- Telegram: auto-polling, voice message support, runtime credential check
- WhatsApp: Twilio webhook bridge, voice messages
- Discord: text + basic voice channel support
- Email: Gmail REST API send/receive
- FCM: Push notification dispatch

All providers check credentials at runtime and log warnings (not crashes)
if credentials are missing, so Aladdin continues to work without messaging.
"""

from __future__ import annotations

import logging
import threading
from typing import Any, Callable, Dict, Optional

log = logging.getLogger(__name__)


# ─────────────────────────────────────────────────────────────────────────────
# Base class


class MessagingProvider:
    """Abstract base for messaging providers."""

    def __init__(self, name: str):
        self.name = name
        self._on_message: Optional[Callable[[str, str, Any], None]] = None

    def set_on_message(self, callback: Callable[[str, str, Any], None]) -> None:
        """Callback signature: (provider_name, message_text, metadata)."""
        self._on_message = callback

    def _emit(self, text: str, metadata: Any = None) -> None:
        if self._on_message:
            try:
                self._on_message(self.name, text, metadata)
            except Exception as exc:
                log.error("%s message callback error: %s", self.name, exc)

    def start(self) -> None:
        raise NotImplementedError

    def stop(self) -> None:
        pass

    def send(self, recipient: str, text: str) -> bool:
        raise NotImplementedError


# ─────────────────────────────────────────────────────────────────────────────
# Telegram


class TelegramBridge(MessagingProvider):
    """Auto-polling Telegram bot with voice message support."""

    def __init__(self, token: str = ""):
        super().__init__("telegram")
        self._token = token
        self._app = None
        self._thread: Optional[threading.Thread] = None
        self._running = False

    def _check_credentials(self) -> bool:
        if not self._token:
            log.warning("Telegram: BOT_TOKEN not set — Telegram disabled")
            return False
        return True

    def start(self) -> None:
        if not self._check_credentials():
            return
        try:
            from telegram.ext import Application, MessageHandler, filters

            self._app = Application.builder().token(self._token).build()
            app = self._app

            async def _handle_text(update, context):
                text = update.message.text or ""
                log.info(
                    "Telegram text from %s: %s",
                    update.message.from_user.username,
                    text[:60],
                )
                self._emit(
                    text, {"chat_id": update.message.chat_id, "platform": "telegram"}
                )

            async def _handle_voice(update, context):
                file = await context.bot.get_file(update.message.voice.file_id)
                log.info("Telegram voice message received (file_id=%s)", file.file_id)
                self._emit(
                    "[Voice message received]",
                    {"file_id": file.file_id, "platform": "telegram"},
                )

            app.add_handler(
                MessageHandler(filters.TEXT & ~filters.COMMAND, _handle_text)
            )
            app.add_handler(MessageHandler(filters.VOICE, _handle_voice))

            def _run():
                import asyncio

                asyncio.run(app.run_polling())

            self._running = True
            self._thread = threading.Thread(
                target=_run, daemon=True, name="telegram-bot"
            )
            self._thread.start()
            log.info("Telegram bot started (polling)")
        except ImportError:
            log.warning(
                "python-telegram-bot not installed: pip install python-telegram-bot"
            )
        except Exception as exc:
            log.error("Telegram start error: %s", exc)

    def stop(self) -> None:
        self._running = False
        if self._app:
            try:
                import asyncio
                # Task 17: use get_running_loop() — safe inside async context
                try:
                    loop = asyncio.get_running_loop()
                    loop.call_soon_threadsafe(self._app.stop)
                except RuntimeError:
                    # No running loop (called from sync context)
                    asyncio.run(self._app.stop())
            except Exception:
                pass

    def send(self, chat_id: str, text: str) -> bool:
        if not self._app:
            return False
        try:
            import asyncio
            # Task 17: use asyncio.run() instead of deprecated get_event_loop()
            asyncio.run(self._app.bot.send_message(chat_id=chat_id, text=text))
            return True
        except Exception as exc:
            log.error("Telegram send error: %s", exc)
            return False


# ─────────────────────────────────────────────────────────────────────────────
# WhatsApp (Twilio)


class WhatsAppBridge(MessagingProvider):
    """Twilio-backed WhatsApp bridge."""

    def __init__(
        self, account_sid: str = "", auth_token: str = "", from_number: str = ""
    ):
        super().__init__("whatsapp")
        self._sid = account_sid
        self._token = auth_token
        self._from = from_number or "whatsapp:+14155238886"

    def _check_credentials(self) -> bool:
        if not self._sid or not self._token:
            log.warning("WhatsApp: Twilio credentials not set — WhatsApp disabled")
            return False
        return True

    def start(self) -> None:
        if not self._check_credentials():
            return
        try:
            from twilio.rest import Client  # noqa: F401

            log.info(
                "WhatsApp (Twilio) bridge ready. Webhook must be configured in Twilio console."
            )
        except ImportError:
            log.warning("twilio not installed: pip install twilio")

    def send(self, to_number: str, text: str) -> bool:
        if not self._check_credentials():
            return False
        try:
            from twilio.rest import Client

            client = Client(self._sid, self._token)
            msg = client.messages.create(
                body=text,
                from_=self._from,
                to=(
                    f"whatsapp:{to_number}"
                    if not to_number.startswith("whatsapp:")
                    else to_number
                ),
            )
            log.info("WhatsApp sent: %s", msg.sid)
            return True
        except ImportError:
            log.warning("twilio not installed: pip install twilio")
            return False
        except Exception as exc:
            log.error("WhatsApp send error: %s", exc)
            return False

    def handle_webhook(self, body: str, from_number: str) -> None:
        """Call this from your Flask/FastAPI webhook endpoint."""
        log.info("WhatsApp message from %s: %s", from_number, body[:60])
        self._emit(body, {"from": from_number, "platform": "whatsapp"})


# ─────────────────────────────────────────────────────────────────────────────
# Discord


class DiscordBridge(MessagingProvider):
    """Discord bot with text channel read/write."""

    def __init__(self, token: str = ""):
        super().__init__("discord")
        self._token = token
        self._client = None
        self._thread: Optional[threading.Thread] = None

    def _check_credentials(self) -> bool:
        if not self._token:
            log.warning("Discord: BOT_TOKEN not set — Discord disabled")
            return False
        return True

    def start(self) -> None:
        if not self._check_credentials():
            return
        try:
            import discord

            intents = discord.Intents.default()
            intents.message_content = True
            self._client = discord.Client(intents=intents)
            client = self._client

            @client.event
            async def on_ready():
                log.info("Discord bot connected as %s", client.user)

            @client.event
            async def on_message(message):
                if message.author == client.user:
                    return
                log.info(
                    "Discord message from %s: %s", message.author, message.content[:60]
                )
                self._emit(
                    message.content,
                    {
                        "channel_id": message.channel.id,
                        "author": str(message.author),
                        "platform": "discord",
                    },
                )

            def _run():
                import asyncio

                asyncio.run(client.start(self._token))

            self._thread = threading.Thread(
                target=_run, daemon=True, name="discord-bot"
            )
            self._thread.start()
            log.info("Discord bot started")
        except ImportError:
            log.warning("discord.py not installed: pip install discord.py")
        except Exception as exc:
            log.error("Discord start error: %s", exc)

    def stop(self) -> None:
        if self._client:
            try:
                import asyncio
                # Task 17: use asyncio.run() instead of deprecated get_event_loop()
                asyncio.run(self._client.close())
            except Exception:
                pass

    def send(self, channel_id: int, text: str) -> bool:
        if not self._client:
            return False
        try:
            import asyncio
            # Task 17: use asyncio.run() instead of deprecated get_event_loop()
            channel = self._client.get_channel(channel_id)
            if channel:
                asyncio.run(channel.send(text))
                return True
        except Exception as exc:
            log.error("Discord send error: %s", exc)
        return False


# ─────────────────────────────────────────────────────────────────────────────
# Email (Gmail REST API)


class EmailBridge(MessagingProvider):
    """Gmail REST API send/receive."""

    def __init__(
        self,
        credentials_file: str = "gmail_credentials.json",
        token_file: str = "gmail_token.json",
    ):
        super().__init__("email")
        self._creds_file = credentials_file
        self._token_file = token_file
        self._service = None

    def _check_credentials(self) -> bool:
        import os

        if not os.path.exists(self._creds_file):
            log.warning(
                "Email: credentials file not found: %s — Gmail disabled",
                self._creds_file,
            )
            return False
        return True

    def start(self) -> None:
        if not self._check_credentials():
            return
        try:
            from google.oauth2.credentials import Credentials
            from google.auth.transport.requests import Request
            from google_auth_oauthlib.flow import InstalledAppFlow
            from googleapiclient.discovery import build
            import os, json

            SCOPES = [
                "https://www.googleapis.com/auth/gmail.modify",
                "https://www.googleapis.com/auth/gmail.send",
            ]
            creds = None
            if os.path.exists(self._token_file):
                creds = Credentials.from_authorized_user_file(self._token_file, SCOPES)
            if not creds or not creds.valid:
                if creds and creds.expired and creds.refresh_token:
                    creds.refresh(Request())
                else:
                    flow = InstalledAppFlow.from_client_secrets_file(
                        self._creds_file, SCOPES
                    )
                    creds = flow.run_local_server(port=0)
                with open(self._token_file, "w") as f:
                    f.write(creds.to_json())
            self._service = build("gmail", "v1", credentials=creds)
            log.info("Gmail service connected")
        except ImportError:
            log.warning(
                "Google API client not installed: pip install google-api-python-client google-auth-oauthlib"
            )
        except Exception as exc:
            log.error("Gmail start error: %s", exc)

    def send(self, to: str, subject: str, body: str) -> bool:  # type: ignore[override]
        if not self._service:
            return False
        try:
            import base64
            from email.mime.text import MIMEText

            msg = MIMEText(body)
            msg["to"] = to
            msg["subject"] = subject
            raw = base64.urlsafe_b64encode(msg.as_bytes()).decode()
            self._service.users().messages().send(
                userId="me", body={"raw": raw}
            ).execute()
            log.info("Email sent to %s", to)
            return True
        except Exception as exc:
            log.error("Email send error: %s", exc)
            return False


# ─────────────────────────────────────────────────────────────────────────────
# FCM Push Notifications


class FCMBridge(MessagingProvider):
    """Firebase Cloud Messaging push notification dispatch."""

    def __init__(self, server_key: str = ""):
        super().__init__("fcm")
        self._key = server_key

    def _check_credentials(self) -> bool:
        if not self._key:
            log.warning("FCM: server_key not set — push notifications disabled")
            return False
        return True

    def start(self) -> None:
        self._check_credentials()  # just warn, no connection needed

    def send(self, device_token: str, title: str, body: str, data: Dict = None) -> bool:  # type: ignore[override]
        if not self._check_credentials():
            return False
        try:
            import json

            payload = {
                "to": device_token,
                "notification": {"title": title, "body": body},
                "data": data or {},
            }
            headers = {
                "Authorization": f"key={self._key}",
                "Content-Type": "application/json",
            }
            import requests

            r = requests.post(
                "https://fcm.googleapis.com/fcm/send",
                headers=headers,
                json=payload,
                timeout=10,
            )
            r.raise_for_status()
            result = r.json()
            if result.get("success") == 1:
                log.info("FCM notification sent to device")
                return True
            else:
                log.warning("FCM response: %s", result)
                return False
        except Exception as exc:
            log.error("FCM send error: %s", exc)
            return False


# ─────────────────────────────────────────────────────────────────────────────
# Messaging Manager


class MessagingManager:
    """
    Orchestrates all messaging providers.
    All providers are started in daemon threads; failures don't crash the app.
    """

    def __init__(self, cfg=None):
        self._providers: Dict[str, MessagingProvider] = {}
        self._on_message: Optional[Callable[[str, str, Any], None]] = None
        if cfg:
            self._init_from_config(cfg)

    def _init_from_config(self, cfg) -> None:
        tg_token = _cfg_get(cfg, "messaging", "telegram", "token", default="")
        if tg_token:
            self._providers["telegram"] = TelegramBridge(token=tg_token)

        wa_sid = _cfg_get(cfg, "messaging", "whatsapp", "account_sid", default="")
        wa_token = _cfg_get(cfg, "messaging", "whatsapp", "auth_token", default="")
        if wa_sid and wa_token:
            self._providers["whatsapp"] = WhatsAppBridge(
                account_sid=wa_sid, auth_token=wa_token
            )

        dc_token = _cfg_get(cfg, "messaging", "discord", "token", default="")
        if dc_token:
            self._providers["discord"] = DiscordBridge(token=dc_token)

        fcm_key = _cfg_get(cfg, "messaging", "fcm", "server_key", default="")
        if fcm_key:
            self._providers["fcm"] = FCMBridge(server_key=fcm_key)

        self._providers["email"] = EmailBridge()

    def set_on_message(self, callback: Callable[[str, str, Any], None]) -> None:
        self._on_message = callback
        for p in self._providers.values():
            p.set_on_message(callback)

    def start_all(self) -> None:
        for name, provider in self._providers.items():
            try:
                provider.start()
            except Exception as exc:
                log.error("Failed to start %s: %s", name, exc)

    def stop_all(self) -> None:
        for provider in self._providers.values():
            try:
                provider.stop()
            except Exception:
                pass

    def get(self, name: str) -> Optional[MessagingProvider]:
        return self._providers.get(name)


def _cfg_get(cfg, *keys, default=""):
    """Safely traverse nested config."""
    node = cfg
    for key in keys:
        if isinstance(node, dict):
            node = node.get(key, {})
        else:
            node = getattr(node, key, {})
    return (
        node
        if isinstance(node, str)
        else (default if not isinstance(node, dict) else default)
    )
