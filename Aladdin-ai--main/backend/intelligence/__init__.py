"""Phase 11 — Intelligence Module for Aladdin AI.

Transforms Aladdin from a reactive chatbot into a proactive, context-aware
personal assistant with Jarvis-level intelligence.

Sub-modules:
  reminder_service      — Proactive, context-aware reminders
  habit_predictor       — ML-based routine learning and action prediction
  briefing_generator    — Personalised daily morning briefings
  news_aggregator       — Multi-source news with deduplication and summarisation
  calendar_optimizer    — Intelligent scheduling and conflict detection
  recommendation_engine — Context-aware content and task recommendations
  context_manager       — Real-time activity, location, device awareness
  location_service      — GPS, geo-fencing, nearby places, ETA
  mood_analyzer         — Multi-signal mood detection and response adaptation
  personalization_manager — Per-user preferences, shortcuts, continuous learning
"""
from .reminder_service import ReminderService, Reminder, PRIORITY_HIGH, PRIORITY_MED, PRIORITY_LOW
from .habit_predictor import HabitPredictor, HabitPrediction
from .briefing_generator import BriefingGenerator, DailyBriefing
from .news_aggregator import NewsAggregator, Article
from .calendar_optimizer import CalendarOptimizer, CalendarEvent, TimeSlot, Conflict
from .recommendation_engine import RecommendationEngine, RecoItem
from .context_manager import ContextManager, Context
from .location_service import LocationService, Place, LocationFix, GeoFenceEvent
from .mood_analyzer import MoodAnalyzer, MoodSnapshot
from .personalization_manager import PersonalizationManager, UserProfile, Shortcut

__all__ = [
    # Reminder service
    "ReminderService", "Reminder",
    "PRIORITY_HIGH", "PRIORITY_MED", "PRIORITY_LOW",
    # Habit predictor
    "HabitPredictor", "HabitPrediction",
    # Daily briefing
    "BriefingGenerator", "DailyBriefing",
    # News
    "NewsAggregator", "Article",
    # Calendar
    "CalendarOptimizer", "CalendarEvent", "TimeSlot", "Conflict",
    # Recommendations
    "RecommendationEngine", "RecoItem",
    # Context
    "ContextManager", "Context",
    # Location
    "LocationService", "Place", "LocationFix", "GeoFenceEvent",
    # Mood
    "MoodAnalyzer", "MoodSnapshot",
    # Personalization
    "PersonalizationManager", "UserProfile", "Shortcut",
]


def create_intelligence_engine(
    context_provider=None,
    voice_callback=None,
    user_id: str = "default",
):
    """
    Factory that wires all Phase 11 modules together into a unified engine.

    Returns a dict of named module instances, fully cross-linked.
    """
    # Core services (no deps)
    location_svc = LocationService()
    mood_analyzer = MoodAnalyzer()
    personalization = PersonalizationManager()
    news_agg = NewsAggregator()
    habit_predictor = HabitPredictor()

    # Services that depend on core
    reminder_svc = ReminderService(context_provider=context_provider)
    context_mgr = ContextManager(
        location_service=location_svc,
    )
    calendar_opt = CalendarOptimizer()
    reco_engine = RecommendationEngine()

    # Briefing depends on almost everything
    briefing = BriefingGenerator(
        reminder_service=reminder_svc,
        news_aggregator=news_agg,
        calendar_optimizer=calendar_opt,
        context_provider=context_provider,
        location_service=location_svc,
    )
    if voice_callback:
        briefing.on_voice(voice_callback)

    return {
        "reminder_service": reminder_svc,
        "habit_predictor": habit_predictor,
        "briefing_generator": briefing,
        "news_aggregator": news_agg,
        "calendar_optimizer": calendar_opt,
        "recommendation_engine": reco_engine,
        "context_manager": context_mgr,
        "location_service": location_svc,
        "mood_analyzer": mood_analyzer,
        "personalization_manager": personalization,
    }
