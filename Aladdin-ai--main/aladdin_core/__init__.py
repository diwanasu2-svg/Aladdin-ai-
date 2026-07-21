"""Aladdin Core — AI Personal Voice Assistant."""

__version__ = "2.2.0"
__author__ = "Aladdin Contributors"

# Smart Memory Part 1
from .user_profile import UserProfile
from .preferences import PreferencesManager
from .facts import FactsManager
from .contacts import ContactsManager
from .memory_manager import MemoryManager

# Smart Memory Part 2 (Phase 3)
from .project_memory import ProjectMemory
from .location_memory import LocationMemory
from .reminder_memory import ReminderMemory
from .calendar_memory import CalendarMemory
from .conversation_summary import ConversationSummary
from .embedding_manager import EmbeddingManager
from .memory_importance import MemoryImportanceScorer
from .memory_ranking import MemoryRanker
from .semantic_search import SemanticSearchEngine
from .vector_store import VectorStore

__all__ = [
    # Smart Memory Part 1
    "ContactsManager",
    "FactsManager",
    "MemoryManager",
    "PreferencesManager",
    "UserProfile",
    # Smart Memory Part 2
    "ProjectMemory",
    "LocationMemory",
    "ReminderMemory",
    "CalendarMemory",
    "ConversationSummary",
    # Smart Memory Part 3
    "EmbeddingManager",
    "MemoryImportanceScorer",
    "MemoryRanker",
    "SemanticSearchEngine",
    "VectorStore",
]
