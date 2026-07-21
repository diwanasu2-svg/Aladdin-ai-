"""Phase 11.3 — Daily Briefing Generator.

Generates a personalised morning briefing including:
  weather · calendar events · reminders · news summary ·
  battery/phone status · travel info · priority tasks · voice output.
"""
from __future__ import annotations
import asyncio
import json
import logging
import time
from dataclasses import dataclass, field
from datetime import datetime, date
from typing import Any, Callable, Dict, List, Optional

from .config import (
    BRIEFING_TIME, BRIEFING_NEWS_COUNT, BRIEFING_CACHE_TTL_S,
    OPENWEATHER_API_KEY,
)

log = logging.getLogger(__name__)


@dataclass
class BriefingSection:
    title: str
    content: str
    emoji: str = ""
    priority: int = 5   # 1 = highest


@dataclass
class DailyBriefing:
    date: str
    user_id: str
    sections: List[BriefingSection] = field(default_factory=list)
    generated_at: float = field(default_factory=time.time)

    def to_text(self) -> str:
        lines = [
            f"🌟 Good morning! Here's your briefing for {self.date}",
            "=" * 50,
        ]
        for s in sorted(self.sections, key=lambda x: x.priority):
            lines.append(f"\n{s.emoji} **{s.title}**")
            lines.append(s.content)
        lines.append("\n" + "=" * 50)
        lines.append("Have a great day! 🚀")
        return "\n".join(lines)

    def to_dict(self) -> Dict:
        return {
            "date": self.date,
            "user_id": self.user_id,
            "generated_at": self.generated_at,
            "sections": [{"title": s.title, "content": s.content,
                          "emoji": s.emoji, "priority": s.priority}
                         for s in self.sections],
            "full_text": self.to_text(),
        }


class BriefingGenerator:
    """Generates and schedules daily morning briefings."""

    def __init__(self,
                 reminder_service=None,
                 news_aggregator=None,
                 calendar_optimizer=None,
                 context_provider: Optional[Callable] = None,
                 location_service=None):
        self._reminders = reminder_service
        self._news = news_aggregator
        self._calendar = calendar_optimizer
        self._ctx = context_provider
        self._location = location_service
        self._cache: Dict[str, DailyBriefing] = {}
        self._voice_cb: Optional[Callable] = None

    def on_voice(self, callback: Callable):
        """Register TTS callback for voice briefing: callback(text)."""
        self._voice_cb = callback

    # ── Section builders ──────────────────────────────────────────────────────
    async def _weather_section(self, lat: float = 0, lon: float = 0,
                                city: str = "") -> Optional[BriefingSection]:
        try:
            import aiohttp
            if not OPENWEATHER_API_KEY:
                return None
            if city:
                url = f"https://api.openweathermap.org/data/2.5/weather?q={city}&appid={OPENWEATHER_API_KEY}&units=metric"
            else:
                url = f"https://api.openweathermap.org/data/2.5/weather?lat={lat}&lon={lon}&appid={OPENWEATHER_API_KEY}&units=metric"
            async with aiohttp.ClientSession() as session:
                async with session.get(url, timeout=aiohttp.ClientTimeout(total=5)) as resp:
                    if resp.status != 200:
                        return None
                    data = await resp.json()
            temp = data["main"]["temp"]
            feels = data["main"]["feels_like"]
            desc = data["weather"][0]["description"].title()
            humidity = data["main"]["humidity"]
            wind = data["wind"]["speed"]
            city_name = data.get("name", city)
            content = (
                f"{city_name}: {temp:.1f}°C (feels like {feels:.1f}°C)\n"
                f"Conditions: {desc}\n"
                f"Humidity: {humidity}% | Wind: {wind} m/s"
            )
            return BriefingSection("Weather", content, "🌤️", priority=1)
        except Exception as e:
            log.warning("Weather fetch error: %s", e)
            return None

    async def _calendar_section(self, user_id: str) -> Optional[BriefingSection]:
        if not self._calendar:
            return None
        try:
            today = date.today()
            events = await self._calendar.get_today_events(user_id)
            if not events:
                return BriefingSection("Calendar", "No events scheduled today. 🎉", "📅", priority=2)
            lines = []
            for ev in events[:8]:
                start = ev.get("start", "")
                title = ev.get("title", ev.get("summary", "Event"))
                lines.append(f"  • {start} — {title}")
            conflicts = await self._calendar.detect_conflicts(user_id)
            if conflicts:
                lines.append(f"\n  ⚠️ {len(conflicts)} scheduling conflict(s) detected!")
            return BriefingSection("Today's Calendar", "\n".join(lines), "📅", priority=2)
        except Exception as e:
            log.warning("Calendar section error: %s", e)
            return None

    async def _reminders_section(self, user_id: str) -> Optional[BriefingSection]:
        if not self._reminders:
            return None
        try:
            now = time.time()
            day_end = now + 86400
            all_rem = self._reminders.list(user_id=user_id)
            today = [r for r in all_rem if now <= r.due_at <= day_end]
            if not today:
                return BriefingSection("Reminders", "No reminders for today.", "🔔", priority=3)
            lines = []
            for r in sorted(today, key=lambda x: x.due_at):
                icon = "🔴" if r.priority == "high" else ("🟡" if r.priority == "medium" else "🟢")
                due = datetime.fromtimestamp(r.due_at).strftime("%H:%M")
                lines.append(f"  {icon} {due} — {r.title}")
            return BriefingSection("Today's Reminders", "\n".join(lines), "🔔", priority=3)
        except Exception as e:
            log.warning("Reminders section error: %s", e)
            return None

    async def _news_section(self, interests: Optional[List[str]] = None) -> Optional[BriefingSection]:
        if not self._news:
            return None
        try:
            articles = await self._news.fetch(interests=interests, max_articles=BRIEFING_NEWS_COUNT)
            if not articles:
                return None
            lines = []
            for a in articles:
                lines.append(f"  • [{a.category.title()}] {a.title}")
                lines.append(f"    {a.summary[:100]}... — {a.source}")
            return BriefingSection("Top News", "\n".join(lines), "📰", priority=4)
        except Exception as e:
            log.warning("News section error: %s", e)
            return None

    async def _device_section(self, context_provider: Optional[Callable] = None) -> Optional[BriefingSection]:
        try:
            lines = []
            if context_provider:
                ctx = await context_provider()
                battery = ctx.get("battery_level")
                charging = ctx.get("charging")
                wifi = ctx.get("wifi")
                if battery is not None:
                    icon = "⚡" if charging else ("🔋" if battery > 30 else "🪫")
                    lines.append(f"  Battery: {battery}% {icon}")
                if wifi is not None:
                    lines.append(f"  Wi-Fi: {'Connected ✅' if wifi else 'Disconnected ❌'}")
            if not lines:
                return None
            return BriefingSection("Device Status", "\n".join(lines), "📱", priority=6)
        except Exception as e:
            log.warning("Device section error: %s", e)
            return None

    async def _travel_section(self, user_id: str) -> Optional[BriefingSection]:
        if not self._location:
            return None
        try:
            eta = await self._location.get_commute_eta(user_id)
            if not eta:
                return None
            content = (
                f"  Commute to office: ~{eta.get('duration_min', '?')} min\n"
                f"  Traffic: {eta.get('traffic', 'Normal')}\n"
                f"  Suggested departure: {eta.get('depart_by', 'Check app')}"
            )
            return BriefingSection("Commute", content, "🚗", priority=5)
        except Exception as e:
            log.warning("Travel section error: %s", e)
            return None

    async def _priority_tasks_section(self, user_id: str) -> Optional[BriefingSection]:
        """Suggest 3 priority tasks for the day based on reminders + calendar gaps."""
        tasks = []
        if self._reminders:
            high = [r for r in self._reminders.list(user_id) if r.priority == "high"]
            tasks.extend(f"  🔴 {r.title}" for r in high[:3])
        if len(tasks) < 3:
            tasks.append("  ✅ Review your goals for the week")
        if len(tasks) < 3:
            tasks.append("  ✅ Clear pending emails/messages")
        return BriefingSection("Priority Tasks", "\n".join(tasks[:5]), "🎯", priority=7)

    # ── Main generator ────────────────────────────────────────────────────────
    async def generate(self, user_id: str = "default",
                       lat: float = 0, lon: float = 0, city: str = "",
                       interests: Optional[List[str]] = None,
                       force: bool = False) -> DailyBriefing:
        today_str = date.today().isoformat()
        cache_key = f"{user_id}:{today_str}"

        if not force and cache_key in self._cache:
            age = time.time() - self._cache[cache_key].generated_at
            if age < BRIEFING_CACHE_TTL_S:
                log.debug("Briefing cache hit for %s", user_id)
                return self._cache[cache_key]

        log.info("Generating daily briefing for user %s", user_id)
        briefing = DailyBriefing(date=today_str, user_id=user_id)

        # Run all section builders in parallel
        tasks = [
            self._weather_section(lat, lon, city),
            self._calendar_section(user_id),
            self._reminders_section(user_id),
            self._news_section(interests),
            self._device_section(self._ctx),
            self._travel_section(user_id),
            self._priority_tasks_section(user_id),
        ]
        results = await asyncio.gather(*tasks, return_exceptions=True)
        for r in results:
            if isinstance(r, BriefingSection):
                briefing.sections.append(r)
            elif isinstance(r, Exception):
                log.warning("Briefing section error: %s", r)

        self._cache[cache_key] = briefing
        log.info("Briefing generated: %d sections", len(briefing.sections))

        # Voice briefing
        if self._voice_cb:
            try:
                voice_text = briefing.to_text()
                if asyncio.iscoroutinefunction(self._voice_cb):
                    await self._voice_cb(voice_text)
                else:
                    self._voice_cb(voice_text)
            except Exception as e:
                log.warning("Voice briefing error: %s", e)

        return briefing

    # ── Scheduler ─────────────────────────────────────────────────────────────
    async def schedule_daily(self, user_id: str = "default", **kwargs):
        """Run the briefing generator every day at BRIEFING_TIME."""
        import asyncio
        while True:
            now = datetime.now()
            h, m = map(int, BRIEFING_TIME.split(":"))
            target = now.replace(hour=h, minute=m, second=0, microsecond=0)
            if now >= target:
                from datetime import timedelta
                target = target + timedelta(days=1)
            wait_s = (target - now).total_seconds()
            log.info("Briefing scheduled in %.0f s (%s)", wait_s, target.isoformat())
            await asyncio.sleep(wait_s)
            try:
                briefing = await self.generate(user_id=user_id, force=True, **kwargs)
                log.info("Daily briefing generated:\n%s", briefing.to_text())
            except Exception as e:
                log.error("Daily briefing failed: %s", e)
