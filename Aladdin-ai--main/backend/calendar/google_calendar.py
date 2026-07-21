"""Google Calendar API integration — OAuth2 two-way sync."""
from __future__ import annotations
import json
import logging
import os
from pathlib import Path
from typing import Any, Dict, List, Optional

log = logging.getLogger(__name__)
_SCOPES = ["https://www.googleapis.com/auth/calendar"]
_TOKEN_PATH = Path("data/google_token.json")
_CREDS_PATH = Path(os.getenv("GOOGLE_CREDENTIALS_PATH", "data/google_credentials.json"))


def _get_service():
    try:
        from google.oauth2.credentials import Credentials
        from google_auth_oauthlib.flow import InstalledAppFlow
        from google.auth.transport.requests import Request
        from googleapiclient.discovery import build
    except ImportError:
        raise RuntimeError("google-api-python-client not installed")

    creds = None
    if _TOKEN_PATH.exists():
        creds = Credentials.from_authorized_user_file(str(_TOKEN_PATH), _SCOPES)
    if not creds or not creds.valid:
        if creds and creds.expired and creds.refresh_token:
            creds.refresh(Request())
        elif _CREDS_PATH.exists():
            flow = InstalledAppFlow.from_client_secrets_file(str(_CREDS_PATH), _SCOPES)
            creds = flow.run_local_server(port=0)
            _TOKEN_PATH.parent.mkdir(parents=True, exist_ok=True)
            _TOKEN_PATH.write_text(creds.to_json())
        else:
            raise RuntimeError(f"Google credentials not found at {_CREDS_PATH}")
    return build("calendar", "v3", credentials=creds)


class GoogleCalendarSync:
    """Two-way sync between local CalendarStore and Google Calendar."""

    def __init__(self, local_store=None) -> None:
        self._local = local_store
        self._available: Optional[bool] = None

    @property
    def available(self) -> bool:
        if self._available is None:
            try:
                _get_service()
                self._available = True
            except Exception:
                self._available = False
        return self._available

    async def list_calendars(self) -> List[Dict]:
        import asyncio
        if not self.available:
            return []
        def _run():
            svc = _get_service()
            result = svc.calendarList().list().execute()
            return result.get("items", [])
        return await asyncio.get_running_loop().run_in_executor(None, _run)

    async def sync_from_google(self, calendar_id: str = "primary",
                                max_results: int = 100) -> List[Dict]:
        """Pull events from Google Calendar into local store."""
        import asyncio, time
        def _run():
            svc = _get_service()
            now_rfc = f"{__import__('datetime').datetime.utcnow().isoformat()}Z"
            result = svc.events().list(
                calendarId=calendar_id, timeMin=now_rfc,
                maxResults=max_results, singleEvents=True,
                orderBy="startTime").execute()
            return result.get("items", [])
        events = await asyncio.get_running_loop().run_in_executor(None, _run)
        synced = []
        for ev in events:
            start = ev.get("start", {})
            end   = ev.get("end", {})
            ts_start = _parse_google_dt(start.get("dateTime") or start.get("date", ""))
            ts_end   = _parse_google_dt(end.get("dateTime")   or end.get("date", ""))
            local_ev = {
                "title": ev.get("summary", ""),
                "description": ev.get("description", ""),
                "location": ev.get("location", ""),
                "start_ts": ts_start, "end_ts": ts_end,
                "google_event_id": ev.get("id"),
            }
            if self._local:
                self._local.upsert_by_google_id(local_ev)
            synced.append(local_ev)
        return synced

    async def push_to_google(self, event: Dict, calendar_id: str = "primary") -> Dict:
        """Push a local event to Google Calendar."""
        import asyncio
        def _run():
            svc = _get_service()
            body = {
                "summary": event.get("title", ""),
                "description": event.get("description", ""),
                "location": event.get("location", ""),
                "start": {"dateTime": _ts_to_rfc(event["start_ts"]), "timeZone": "UTC"},
                "end":   {"dateTime": _ts_to_rfc(event.get("end_ts") or event["start_ts"]+3600), "timeZone": "UTC"},
            }
            if event.get("google_event_id"):
                return svc.events().update(calendarId=calendar_id,
                                           eventId=event["google_event_id"], body=body).execute()
            return svc.events().insert(calendarId=calendar_id, body=body).execute()
        return await asyncio.get_running_loop().run_in_executor(None, _run)


def _parse_google_dt(s: str) -> float:
    if not s:
        return 0.0
    from datetime import datetime
    for fmt in ("%Y-%m-%dT%H:%M:%S%z", "%Y-%m-%dT%H:%M:%SZ", "%Y-%m-%d"):
        try:
            return datetime.strptime(s, fmt).timestamp()
        except ValueError:
            continue
    return 0.0


def _ts_to_rfc(ts: float) -> str:
    from datetime import datetime, timezone
    return datetime.fromtimestamp(ts, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
