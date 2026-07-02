"""
Email tool — Task 17: asyncio.get_running_loop() → asyncio.get_running_loop().
Task 37: SMTP TLS certificate verification (ssl.create_default_context).
"""
from __future__ import annotations
import asyncio, email, imaplib, logging, os, smtplib, ssl, time
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
from email.mime.base import MIMEBase
from email import encoders
from pathlib import Path
from typing import List, Optional
from .base import BaseTool, ToolResult

log = logging.getLogger(__name__)


class SendEmailTool(BaseTool):
    name = "send_email"
    description = "Send an email via SMTP."
    parameters = {"type": "object", "properties": {
        "to": {"type": "string", "description": "Recipient email"},
        "subject": {"type": "string"}, "body": {"type": "string"},
        "cc": {"type": "string"}, "attachments": {"type": "array", "items": {"type": "string"}}},
        "required": ["to", "subject", "body"]}

    async def execute(self, to: str, subject: str, body: str,
                      cc: str = "", attachments: Optional[List[str]] = None) -> ToolResult:
        t0 = time.time()
        smtp_host = os.getenv("SMTP_HOST", "smtp.gmail.com")
        smtp_port = int(os.getenv("SMTP_PORT", "587"))
        smtp_user = os.getenv("SMTP_USER", "")
        smtp_pass = os.getenv("SMTP_PASS", "")
        if not smtp_user:
            return ToolResult(False, self.name, error="SMTP_USER env var not set")

        def _send():
            msg = MIMEMultipart()
            msg["From"] = smtp_user
            msg["To"] = to
            msg["Subject"] = subject
            if cc:
                msg["Cc"] = cc
            msg.attach(MIMEText(body, "plain"))
            for att in (attachments or []):
                p = Path(att)
                if p.exists():
                    part = MIMEBase("application", "octet-stream")
                    part.set_payload(p.read_bytes())
                    encoders.encode_base64(part)
                    part.add_header("Content-Disposition", f"attachment; filename={p.name}")
                    msg.attach(part)

            # Task 37: Use ssl.create_default_context() for TLS certificate verification
            context = ssl.create_default_context()
            recipients = [to] + ([cc] if cc else [])

            if smtp_port == 465:
                # SMTP_SSL — wraps connection in TLS from the start
                with smtplib.SMTP_SSL(smtp_host, smtp_port, context=context) as srv:
                    srv.login(smtp_user, smtp_pass)
                    srv.sendmail(smtp_user, recipients, msg.as_string())
            else:
                # STARTTLS (port 587) — upgrades to TLS after initial plaintext greeting
                with smtplib.SMTP(smtp_host, smtp_port) as srv:
                    srv.ehlo()
                    srv.starttls(context=context)   # Task 37: verifies server cert
                    srv.ehlo()
                    srv.login(smtp_user, smtp_pass)
                    srv.sendmail(smtp_user, recipients, msg.as_string())

        try:
            # Task 17: get_running_loop() instead of deprecated get_event_loop()
            loop = asyncio.get_running_loop()
            await loop.run_in_executor(None, _send)
            return ToolResult(True, self.name, {"to": to, "subject": subject},
                              duration_ms=(time.time() - t0) * 1000)
        except ssl.SSLCertVerificationError as exc:
            log.error("SMTP TLS certificate verification failed: %s", exc)
            return ToolResult(False, self.name, error=f"TLS verification failed: {exc}",
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            log.error("Send email error: %s", exc)
            return ToolResult(False, self.name, error=str(exc),
                              duration_ms=(time.time() - t0) * 1000)


class ReadEmailsTool(BaseTool):
    name = "read_emails"
    description = "Read recent emails from IMAP inbox."
    parameters = {"type": "object", "properties": {
        "count": {"type": "integer", "default": 10},
        "folder": {"type": "string", "default": "INBOX"},
        "unread_only": {"type": "boolean", "default": False}}}

    async def execute(self, count: int = 10, folder: str = "INBOX", unread_only: bool = False) -> ToolResult:
        t0 = time.time()
        imap_host = os.getenv("IMAP_HOST", "imap.gmail.com")
        imap_user = os.getenv("IMAP_USER", os.getenv("SMTP_USER", ""))
        imap_pass = os.getenv("IMAP_PASS", os.getenv("SMTP_PASS", ""))
        if not imap_user:
            return ToolResult(False, self.name, error="IMAP_USER env var not set")

        def _read():
            # Task 37: imaplib.IMAP4_SSL uses ssl.create_default_context() by default → TLS verified
            mail = imaplib.IMAP4_SSL(imap_host)
            mail.login(imap_user, imap_pass)
            mail.select(folder)
            criteria = "(UNSEEN)" if unread_only else "ALL"
            _, ids = mail.search(None, criteria)
            ids_list = ids[0].split()[-count:]
            emails = []
            for eid in reversed(ids_list):
                _, data = mail.fetch(eid, "(RFC822)")
                msg = email.message_from_bytes(data[0][1])
                body = ""
                if msg.is_multipart():
                    for part in msg.walk():
                        if part.get_content_type() == "text/plain":
                            body = part.get_payload(decode=True).decode(errors="ignore")
                            break
                else:
                    body = msg.get_payload(decode=True).decode(errors="ignore")
                emails.append({
                    "id": eid.decode(),
                    "from": msg.get("From"),
                    "subject": msg.get("Subject"),
                    "date": msg.get("Date"),
                    "body": body[:500],
                })
            mail.logout()
            return emails

        try:
            # Task 17: get_running_loop() instead of deprecated get_event_loop()
            loop = asyncio.get_running_loop()
            result = await loop.run_in_executor(None, _read)
            return ToolResult(True, self.name, {"emails": result, "count": len(result)},
                              duration_ms=(time.time() - t0) * 1000)
        except Exception as exc:
            log.error("Read emails error: %s", exc)
            return ToolResult(False, self.name, error=str(exc),
                              duration_ms=(time.time() - t0) * 1000)
