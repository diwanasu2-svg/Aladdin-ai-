"""
Aladdin WhatsApp Integration
=============================
Two modes:
  1. Twilio (requires TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, TWILIO_WHATSAPP_FROM)
  2. Local webhook receiver (listens on /webhook/whatsapp)

Twilio setup:
  - pip install twilio
  - Set environment variables or config.yaml whatsapp section
  - Point Twilio Sandbox webhook to: https://YOUR_NGROK/webhook/whatsapp

Usage:
  From main.py:
      if cfg.whatsapp.enabled:
          wa = WhatsAppBot(cfg.whatsapp, llm, memory)
          wa.start()
"""

from __future__ import annotations

import logging
import os
import threading
from typing import TYPE_CHECKING, Optional

from .config import WhatsAppCfg

if TYPE_CHECKING:
    from .llm import OllamaClient
    from .memory import ConversationMemory

log = logging.getLogger(__name__)


class WhatsAppBot:
    """
    WhatsApp bot via Twilio API.
    Falls back gracefully if twilio is not installed.
    """

    def __init__(
        self,
        cfg: WhatsAppCfg,
        llm: "OllamaClient",
        memory: "ConversationMemory",
    ):
        self.cfg = cfg
        self.llm = llm
        self.memory = memory
        self._client = None
        self._from_number = None
        self._app = None

        if not cfg.enabled:
            return

        # Try to init Twilio client
        account_sid = os.environ.get("TWILIO_ACCOUNT_SID", "")
        auth_token = os.environ.get("TWILIO_AUTH_TOKEN", "")
        from_number = os.environ.get(
            "TWILIO_WHATSAPP_FROM",
            f"whatsapp:{cfg.phone_number}" if cfg.phone_number else "",
        )

        if account_sid and auth_token:
            try:
                from twilio.rest import Client  # type: ignore

                self._client = Client(account_sid, auth_token)
                self._from_number = from_number
                log.info("Twilio WhatsApp client initialised (from: %s)", from_number)
            except ImportError:
                log.warning(
                    "twilio not installed — WhatsApp via Twilio disabled. "
                    "Run: pip install twilio"
                )
        else:
            log.warning(
                "TWILIO_ACCOUNT_SID / TWILIO_AUTH_TOKEN not set — WhatsApp disabled."
            )

    def send(self, to: str, body: str) -> bool:
        """Send a WhatsApp message via Twilio."""
        if not self._client or not self._from_number:
            log.warning("Cannot send WhatsApp — Twilio not configured.")
            return False
        try:
            self._client.messages.create(
                from_=self._from_number,
                to=f"whatsapp:{to}" if not to.startswith("whatsapp:") else to,
                body=body,
            )
            return True
        except Exception as exc:
            log.error("WhatsApp send failed: %s", exc)
            return False

    def start_webhook_server(self, port: int = 5000) -> None:
        """
        Start a Flask webhook server to receive incoming WhatsApp messages.
        Requires: pip install flask twilio
        """
        try:
            from flask import Flask, request  # type: ignore
            from twilio.twiml.messaging_response import MessagingResponse  # type: ignore
        except ImportError:
            log.warning("Flask / twilio not installed. Webhook server not started.")
            return

        flask_app = Flask("aladdin_whatsapp")

        @flask_app.route("/webhook/whatsapp", methods=["POST"])
        def webhook():
            incoming = request.form.get("Body", "").strip()
            sender = request.form.get("From", "unknown")
            log.info("WhatsApp from %s: %s", sender, incoming[:80])

            history = self.memory.recent(8)
            reply = self.llm.chat(incoming, history=history)
            self.memory.append(incoming, reply)

            resp = MessagingResponse()
            resp.message(reply)
            return str(resp)

        def _run():
            flask_app.run(host="0.0.0.0", port=port, debug=False, use_reloader=False)

        t = threading.Thread(target=_run, daemon=True, name="whatsapp-webhook")
        t.start()
        log.info("WhatsApp webhook server started on port %d", port)
        log.info("  Point Twilio to: https://<your-ngrok>/webhook/whatsapp")

    def start(self) -> None:
        """Start the WhatsApp bot (webhook mode)."""
        if not self.cfg.enabled:
            return
        self.start_webhook_server()
