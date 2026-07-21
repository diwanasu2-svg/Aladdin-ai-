"""Unit tests for Phase 11 — Intelligence modules.

Run with: pytest tests/test_intelligence.py -v
"""
import asyncio
import os
import sys
import time

import pytest

# Ensure backend is importable
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

# Override DB paths to use temp files during testing
os.environ.setdefault("REMINDER_DB_TEST", ":memory:")


# ═══════════════════════════════════════════════════════════════════════════════
# ReminderService Tests
# ═══════════════════════════════════════════════════════════════════════════════
class TestReminderService:
    def setup_method(self):
        from backend.intelligence.reminder_service import ReminderService
        import tempfile, sqlite3
        self.tmp = tempfile.mktemp(suffix=".db")
        import backend.intelligence.config as cfg
        cfg.REMINDER_DB = self.tmp
        self.svc = ReminderService()

    def teardown_method(self):
        try:
            os.unlink(self.tmp)
        except Exception:
            pass

    def test_add_reminder(self):
        r = self.svc.add("Test reminder", body="Do something", priority="high",
                         due_at=time.time() + 3600)
        assert r.id
        assert r.title == "Test reminder"
        assert r.priority == "high"

    def test_list_reminders(self):
        self.svc.add("R1", due_at=time.time() + 100)
        self.svc.add("R2", due_at=time.time() + 200)
        items = self.svc.list()
        assert len(items) >= 2

    def test_acknowledge_reminder(self):
        r = self.svc.add("Ack test", due_at=time.time() + 100)
        self.svc.acknowledge(r.id)
        updated = self.svc.get(r.id)
        assert updated.acknowledged

    def test_snooze_reminder(self):
        r = self.svc.add("Snooze test", due_at=time.time() + 10)
        original_due = r.due_at
        snoozed = self.svc.snooze(r.id, minutes=15)
        assert snoozed.due_at > original_due
        assert snoozed.snooze_count == 1

    def test_snooze_respects_max(self):
        from backend.intelligence.reminder_service import REMINDER_MAX_SNOOZE_COUNT
        r = self.svc.add("Max snooze", due_at=time.time())
        for _ in range(REMINDER_MAX_SNOOZE_COUNT + 1):
            result = self.svc.snooze(r.id, minutes=1)
        assert result.snooze_count <= REMINDER_MAX_SNOOZE_COUNT

    def test_suggest_reschedule(self):
        r = self.svc.add("High priority", priority="high", due_at=time.time())
        now = time.time()
        slots = [now + 3600, now + 7200, now + 10800]
        chosen = self.svc.suggest_reschedule(r.id, slots)
        assert chosen == slots[0]   # high priority → earliest slot

    def test_priority_weights(self):
        from backend.intelligence.reminder_service import PRIORITY_WEIGHTS
        assert PRIORITY_WEIGHTS["high"] > PRIORITY_WEIGHTS["medium"]
        assert PRIORITY_WEIGHTS["medium"] > PRIORITY_WEIGHTS["low"]

    def test_delete_reminder(self):
        r = self.svc.add("To delete", due_at=time.time() + 1000)
        self.svc.delete(r.id)
        assert self.svc.get(r.id) is None


# ═══════════════════════════════════════════════════════════════════════════════
# HabitPredictor Tests
# ═══════════════════════════════════════════════════════════════════════════════
class TestHabitPredictor:
    def setup_method(self):
        import tempfile, backend.intelligence.config as cfg
        self.tmp = tempfile.mktemp(suffix=".db")
        cfg.HABIT_DB = self.tmp
        from backend.intelligence.habit_predictor import HabitPredictor
        self.hp = HabitPredictor()

    def teardown_method(self):
        try:
            os.unlink(self.tmp)
        except Exception:
            pass

    def test_record_event(self):
        self.hp.record("open_app:spotify", user_id="u1")
        self.hp.record("open_app:spotify", user_id="u1")
        with self.hp._db() as db:
            count = db.execute("SELECT COUNT(*) FROM events WHERE user_id='u1'").fetchone()[0]
        assert count == 2

    def test_analyse_patterns_empty(self):
        patterns = self.hp.analyse_patterns("no_user")
        assert patterns == []

    def test_analyse_patterns_finds_recurring(self):
        import backend.intelligence.config as cfg
        cfg.HABIT_MIN_OCCURRENCES = 2
        for _ in range(4):
            self.hp.record("check_email", user_id="u2")
        patterns = self.hp.analyse_patterns("u2")
        actions = [p["action"] for p in patterns]
        assert "check_email" in actions

    def test_predict_returns_list(self):
        preds = self.hp.predict_next("nobody")
        assert isinstance(preds, list)

    def test_proactive_suggestions(self):
        suggestions = self.hp.get_proactive_suggestions("nobody")
        assert isinstance(suggestions, list)


# ═══════════════════════════════════════════════════════════════════════════════
# MoodAnalyzer Tests
# ═══════════════════════════════════════════════════════════════════════════════
class TestMoodAnalyzer:
    def setup_method(self):
        import tempfile, backend.intelligence.config as cfg
        self.tmp = tempfile.mktemp(suffix=".db")
        cfg.MOOD_DB = self.tmp
        from backend.intelligence.mood_analyzer import MoodAnalyzer
        self.ma = MoodAnalyzer()

    def teardown_method(self):
        try:
            os.unlink(self.tmp)
        except Exception:
            pass

    def test_text_happy(self):
        signal = self.ma._analyse_text("I am so happy and excited today!")
        assert signal.valence > 0

    def test_text_stressed(self):
        signal = self.ma._analyse_text("This is an emergency! URGENT help needed ASAP!")
        from backend.intelligence.mood_analyzer import MOOD_STRESSED, MOOD_FRUSTRATED
        assert signal.mood in (MOOD_STRESSED, MOOD_FRUSTRATED)

    def test_text_sad(self):
        signal = self.ma._analyse_text("I feel really bad and terrible today")
        assert signal.valence < 0

    def test_text_neutral(self):
        signal = self.ma._analyse_text("Please check the file")
        from backend.intelligence.mood_analyzer import MOOD_NEUTRAL, MOOD_CALM
        assert signal.mood in (MOOD_NEUTRAL, MOOD_CALM)

    def test_audio_tired(self):
        signal = self.ma.analyse_audio({"speech_rate_wpm": 60, "energy_db": -35,
                                         "pitch_hz": 100, "pause_ratio": 0.6})
        from backend.intelligence.mood_analyzer import MOOD_TIRED
        assert signal.mood == MOOD_TIRED

    def test_audio_excited(self):
        signal = self.ma.analyse_audio({"speech_rate_wpm": 200, "energy_db": -5,
                                         "pitch_hz": 200, "pause_ratio": 0.05})
        from backend.intelligence.mood_analyzer import MOOD_EXCITED
        assert signal.mood == MOOD_EXCITED

    def test_fuse_empty(self):
        mood, conf, val, arou = self.ma.fuse([])
        from backend.intelligence.mood_analyzer import MOOD_NEUTRAL
        assert mood == MOOD_NEUTRAL

    def test_fuse_multi_signal(self):
        s1 = self.ma._analyse_text("Amazing day!")
        s2 = self.ma.analyse_audio({"speech_rate_wpm": 170, "energy_db": -8,
                                     "pitch_hz": 180, "pause_ratio": 0.05})
        mood, conf, val, arou = self.ma.fuse([s1, s2])
        assert val > 0

    def test_analyse_async(self):
        async def run():
            snap = await self.ma.analyse("u1", text="What a wonderful day!")
            return snap
        snap = asyncio.run(run())
        assert snap.user_id == "u1"
        assert snap.valence > 0

    def test_adapt_response_stressed(self):
        async def run():
            await self.ma.analyse("u2", text="URGENT EMERGENCY help NOW!")
            return self.ma.adapt_response("u2", "Here is the answer.")
        result = asyncio.run(run())
        assert "tone" in result
        assert result["disclaimer"]

    def test_history_stored(self):
        async def run():
            await self.ma.analyse("u3", text="I feel great!")
        asyncio.run(run())
        history = self.ma.get_history("u3", days=1)
        assert len(history) >= 1

    def test_mood_trend(self):
        async def run():
            for text in ["great!", "awesome!", "wonderful!", "good!", "nice!"]:
                await self.ma.analyse("u4", text=text)
        asyncio.run(run())
        trend = self.ma.get_mood_trend("u4", days=1)
        assert "trend" in trend
        assert "dominant_mood" in trend


# ═══════════════════════════════════════════════════════════════════════════════
# PersonalizationManager Tests
# ═══════════════════════════════════════════════════════════════════════════════
class TestPersonalizationManager:
    def setup_method(self):
        import tempfile, backend.intelligence.config as cfg
        self.tmp = tempfile.mktemp(suffix=".db")
        cfg.PERSONA_DB = self.tmp
        from backend.intelligence.personalization_manager import PersonalizationManager
        self.pm = PersonalizationManager()

    def teardown_method(self):
        try:
            os.unlink(self.tmp)
        except Exception:
            pass

    def test_default_profile_exists(self):
        profile = self.pm.get_profile()
        assert profile.user_id == "default"

    def test_create_and_get_profile(self):
        from backend.intelligence.personalization_manager import UserProfile
        p = UserProfile(user_id="alice", display_name="Alice", language="fr", tone="formal")
        self.pm.create_profile(p)
        retrieved = self.pm.get_profile("alice")
        assert retrieved.language == "fr"
        assert retrieved.tone == "formal"

    def test_update_profile(self):
        self.pm.update_profile("default", tone="casual", verbosity="minimal")
        p = self.pm.get_profile("default")
        assert p.tone == "casual"
        assert p.verbosity == "minimal"

    def test_set_get_preference(self):
        self.pm.set_preference("default", "app", "spotify", True, weight=2.0)
        val = self.pm.get_preference("default", "app", "spotify")
        assert val is True

    def test_preference_not_found(self):
        val = self.pm.get_preference("default", "app", "nonexistent", default="missing")
        assert val == "missing"

    def test_top_preferences(self):
        for _ in range(3):
            self.pm.set_preference("default", "app", "spotify", True, weight=1.0)
        for _ in range(2):
            self.pm.set_preference("default", "app", "youtube", True, weight=1.0)
        tops = self.pm.get_top_preferences("default", "app", top_n=2)
        assert tops[0][0] == "spotify"   # highest total weight

    def test_shortcut_add_and_match(self):
        self.pm.add_shortcut("default", "morning", "start my day",
                             [{"tool": "briefing", "params": {}}])
        sc = self.pm.match_shortcut("default", "start my day please")
        assert sc is not None
        assert sc.name == "morning"

    def test_shortcut_no_match(self):
        sc = self.pm.match_shortcut("default", "random text xyz")
        assert sc is None

    def test_list_users(self):
        from backend.intelligence.personalization_manager import UserProfile
        self.pm.create_profile(UserProfile(user_id="bob"))
        users = self.pm.list_users()
        assert "bob" in users

    def test_delete_profile(self):
        from backend.intelligence.personalization_manager import UserProfile
        self.pm.create_profile(UserProfile(user_id="temp_user"))
        self.pm.delete_profile("temp_user")
        assert "temp_user" not in self.pm.list_users()

    def test_observe_infers_preference(self):
        self.pm.observe("default", "opened_app", {"app": "telegram"})
        val = self.pm.get_preference("default", "app", "telegram")
        assert val is True

    def test_personalize_response_minimal(self):
        self.pm.update_profile("default", verbosity="minimal")
        long_text = "This is sentence one. This is sentence two. This is sentence three. And four."
        result = self.pm.personalize_response("default", long_text)
        assert len(result) < len(long_text)

    def test_personalize_response_formal(self):
        self.pm.update_profile("default", tone="formal")
        result = self.pm.personalize_response("default", "it's great and you're welcome")
        assert "it is" in result or "you are" in result

    def test_personalization_summary(self):
        summary = self.pm.get_personalization_summary("default")
        assert "profile" in summary
        assert "shortcuts" in summary


# ═══════════════════════════════════════════════════════════════════════════════
# LocationService Tests
# ═══════════════════════════════════════════════════════════════════════════════
class TestLocationService:
    def setup_method(self):
        import tempfile, backend.intelligence.config as cfg
        self.tmp = tempfile.mktemp(suffix=".db")
        cfg.LOCATION_DB = self.tmp
        from backend.intelligence.location_service import LocationService
        self.ls = LocationService()

    def teardown_method(self):
        try:
            os.unlink(self.tmp)
        except Exception:
            pass

    def test_save_place(self):
        p = self.ls.save_place("cafe", lat=51.5, lng=-0.12, radius_m=100)
        assert p.label == "cafe"

    def test_set_home(self):
        self.ls.set_home(48.8566, 2.3522)
        places = self.ls.list_places()
        home = next((p for p in places if p.place_type == "home"), None)
        assert home is not None
        assert home.lat == pytest.approx(48.8566, abs=0.001)

    def test_haversine_distance(self):
        from backend.intelligence.location_service import haversine
        # Eiffel Tower to Notre Dame ≈ 3.8 km
        d = haversine(48.8584, 2.2945, 48.8530, 2.3499)
        assert 3000 < d < 5000

    def test_nearest_place_label(self):
        self.ls.save_place("my_office", lat=40.7128, lng=-74.0060, radius_m=300)
        label = self.ls._nearest_place_label(40.7130, -74.0058, "default")
        assert label == "my_office"

    def test_nearest_place_unknown(self):
        label = self.ls._nearest_place_label(0.0, 0.0, "default")
        assert label == "unknown"

    def test_list_places_empty(self):
        from backend.intelligence.location_service import LocationService
        import backend.intelligence.config as cfg
        import tempfile
        cfg.LOCATION_DB = tempfile.mktemp(suffix=".db")
        ls2 = LocationService()
        assert ls2.list_places("nobody") == []


# ═══════════════════════════════════════════════════════════════════════════════
# CalendarOptimizer Tests
# ═══════════════════════════════════════════════════════════════════════════════
class TestCalendarOptimizer:
    def setup_method(self):
        from backend.intelligence.calendar_optimizer import CalendarOptimizer
        self.co = CalendarOptimizer()

    def _add_event(self, title, start_h, end_h, location=""):
        from backend.intelligence.calendar_optimizer import CalendarEvent
        from datetime import date, datetime
        today = date.today()
        start = datetime.combine(today, datetime.min.time()).replace(hour=start_h)
        end = datetime.combine(today, datetime.min.time()).replace(hour=end_h)
        ev = CalendarEvent(id=f"{title}-id", title=title, start=start, end=end, location=location)
        self.co.add_manual_event("default", ev)
        return ev

    def test_find_free_slots_no_events(self):
        async def run():
            return await self.co.find_free_slots("no_user")
        slots = asyncio.run(run())
        assert len(slots) > 0

    def test_find_free_slots_with_events(self):
        self._add_event("Morning standup", 9, 10)
        self._add_event("Lunch meeting", 12, 13)

        async def run():
            return await self.co.find_free_slots("default", min_duration_min=30)
        slots = asyncio.run(run())
        assert len(slots) >= 2

    def test_detect_conflicts(self):
        self._add_event("Meeting A", 10, 12)
        self._add_event("Meeting B", 11, 13)

        async def run():
            return await self.co.detect_conflicts("default")
        conflicts = asyncio.run(run())
        assert len(conflicts) >= 1

    def test_no_conflicts_non_overlapping(self):
        from backend.intelligence.calendar_optimizer import CalendarOptimizer, CalendarEvent
        co2 = CalendarOptimizer()
        from datetime import date, datetime
        today = date.today()
        ev1 = CalendarEvent("1", "A",
                            datetime.combine(today, datetime.min.time()).replace(hour=9),
                            datetime.combine(today, datetime.min.time()).replace(hour=10))
        ev2 = CalendarEvent("2", "B",
                            datetime.combine(today, datetime.min.time()).replace(hour=11),
                            datetime.combine(today, datetime.min.time()).replace(hour=12))
        co2.add_manual_event("u", ev1)
        co2.add_manual_event("u", ev2)

        async def run():
            return await co2.detect_conflicts("u")
        conflicts = asyncio.run(run())
        assert conflicts == []

    def test_schedule_recommendations(self):
        report = self.co._schedule_recommendations([], [], [])
        assert isinstance(report, list)


# ═══════════════════════════════════════════════════════════════════════════════
# NewsAggregator Tests
# ═══════════════════════════════════════════════════════════════════════════════
class TestNewsAggregator:
    def setup_method(self):
        from backend.intelligence.news_aggregator import NewsAggregator, Article
        self.na = NewsAggregator()
        self.Article = Article

    def test_classify_technology(self):
        from backend.intelligence.news_aggregator import _classify
        result = _classify("new AI software startup app developer")
        assert result == "technology"

    def test_classify_sports(self):
        from backend.intelligence.news_aggregator import _classify
        result = _classify("football champion league match tournament")
        assert result == "sports"

    def test_summarise_short(self):
        from backend.intelligence.news_aggregator import _summarise
        text = "Hello world."
        result = _summarise(text, 50)
        assert "Hello" in result

    def test_summarise_long(self):
        from backend.intelligence.news_aggregator import _summarise
        text = "Word " * 300
        result = _summarise(text, 50)
        word_count = len(result.split())
        assert word_count <= 60  # some buffer for the summariser

    def test_article_creation(self):
        a = self.Article(title="AI beats humans at chess", url="https://example.com",
                         source="TechNews")
        assert a.category == "technology"
        assert a.fingerprint

    def test_deduplicate_removes_similar(self):
        from backend.intelligence.news_aggregator import Article
        a1 = Article("OpenAI releases new GPT model", "https://a.com", "Source1")
        a2 = Article("OpenAI releases new GPT model today", "https://b.com", "Source2")
        result = self.na._deduplicate([a1, a2])
        assert len(result) == 1

    def test_deduplicate_keeps_different(self):
        from backend.intelligence.news_aggregator import Article
        a1 = Article("Apple launches iPhone 16", "https://a.com", "S1")
        a2 = Article("Google releases Android update", "https://b.com", "S2")
        result = self.na._deduplicate([a1, a2])
        assert len(result) == 2

    def test_filter_by_interests(self):
        from backend.intelligence.news_aggregator import Article
        tech = Article("AI software", "https://tech.com", "TechSource")
        sports = Article("Football match result", "https://sport.com", "SportsSource")
        filtered = self.na._filter_by_interests([tech, sports], ["technology", "ai"])
        assert filtered[0].category == "technology"


# ═══════════════════════════════════════════════════════════════════════════════
# RecommendationEngine Tests
# ═══════════════════════════════════════════════════════════════════════════════
class TestRecommendationEngine:
    def setup_method(self):
        import tempfile, backend.intelligence.config as cfg
        self.tmp = tempfile.mktemp(suffix=".db")
        cfg.RECO_DB = self.tmp
        from backend.intelligence.recommendation_engine import RecommendationEngine
        self.re = RecommendationEngine()

    def teardown_method(self):
        try:
            os.unlink(self.tmp)
        except Exception:
            pass

    def test_record_and_retrieve(self):
        self.re.record("u1", "spotify", "music", "open", title="Spotify")
        self.re.record("u1", "spotify", "music", "like", title="Spotify")
        items = self.re._frequency_recommend("u1", "music", 5)
        assert any(i.item_id == "spotify" for i in items)

    def test_empty_recommendations(self):
        items = self.re._frequency_recommend("nobody", None, 5)
        assert items == []

    def test_feedback_recorded(self):
        self.re.record("u2", "youtube", "video", "view", title="YouTube")
        self.re.feedback("u2", "youtube", -1.0)
        with self.re._db() as db:
            row = db.execute("SELECT score FROM feedback WHERE user_id='u2'").fetchone()
        assert row["score"] == -1.0

    def test_context_filter_removes_disliked(self):
        self.re.record("u3", "tiktok", "video", "view", title="TikTok")
        self.re.feedback("u3", "tiktok", -1.0)
        items = self.re._frequency_recommend("u3", "video", 5)
        filtered = self.re._apply_context_filter(items, {})
        assert not any(i.item_id == "tiktok" for i in filtered)

    def test_async_recommend(self):
        self.re.record("u4", "chrome", "app", "open", title="Chrome")
        self.re.record("u4", "chrome", "app", "open", title="Chrome")

        async def run():
            return await self.re.recommend("u4", item_type="app")
        items = asyncio.run(run())
        assert isinstance(items, list)


if __name__ == "__main__":
    pytest.main([__file__, "-v", "--tb=short"])
