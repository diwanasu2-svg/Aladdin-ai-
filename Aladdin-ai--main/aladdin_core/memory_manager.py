"""Central memory management system — coordinates all memory layers.

Phase 3 — Smart Memory Part 3:
  Adds memory importance scoring, memory ranking, semantic search,
  embedding management and a persistent vector database — all while
  preserving the Part 1 + Part 2 APIs and behavior.
"""

from __future__ import annotations

import json
import logging
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional

try:  # pragma: no cover - import path resolution
    from .config import MemoryCfg  # type: ignore
except ImportError:  # pragma: no cover
    try:
        from config import MemoryCfg  # type: ignore
    except ImportError:
        from dataclasses import dataclass

        @dataclass
        class MemoryCfg:  # type: ignore
            db_path: str = "data/aladdin_memory.sqlite"
            window: int = 12
            semantic_search_enabled: bool = True
            max_facts: int = 500
            summarize_after: int = 50
            profile_path: str = "data/user_profile.json"
            project_memory_enabled: bool = True
            project_memory_db_path: str = "data/project_memory.sqlite"
            location_memory_enabled: bool = True
            location_memory_db_path: str = "data/location_memory.sqlite"
            reminder_memory_enabled: bool = True
            reminder_memory_db_path: str = "data/reminder_memory.sqlite"
            calendar_memory_enabled: bool = True
            calendar_memory_db_path: str = "data/calendar_memory.sqlite"
            conversation_summary_enabled: bool = True
            conversation_summary_db_path: str = "data/conversation_summary.sqlite"
            summary_trigger_messages: int = 30
            summary_max_length: int = 500
            importance_scoring_enabled: bool = True
            vector_store_enabled: bool = True
            vector_store_db_path: str = "data/memory_vectors.sqlite"
            embedding_dimensions: int = 256
            embedding_cache_size: int = 1024
            semantic_search_default_limit: int = 5
            semantic_search_min_similarity: float = 0.12
            semantic_search_rebuild_on_start: bool = True
            ranking_similarity_bias: float = 0.55
            ranking_importance_bias: float = 0.30
            ranking_recency_bias: float = 0.15


from .calendar_memory import CalendarMemory
from .contacts import ContactsManager
from .conversation_summary import ConversationSummary
from .embedding_manager import EmbeddingManager
from .facts import FactsManager
from .location_memory import LocationMemory
from .memory_importance import MemoryImportanceScorer
from .memory_ranking import MemoryRanker
from .preferences import PreferencesManager
from .project_memory import ProjectMemory
from .reminder_memory import ReminderMemory
from .semantic_search import SemanticSearchEngine
from .user_profile import UserProfile
from .vector_store import VectorStore

log = logging.getLogger(__name__)


def _cfg(cfg: Any, key: str, default: Any) -> Any:
    """Read a config value tolerantly (dataclass, dict, or namespace)."""
    if hasattr(cfg, key):
        return getattr(cfg, key)
    if isinstance(cfg, dict):
        return cfg.get(key, default)
    return default


class MemoryManager:
    """Unified memory orchestrator for Smart Memory Parts 1, 2 and 3."""

    def __init__(self, cfg: MemoryCfg):
        self.cfg = cfg
        db_path = Path(cfg.db_path)
        db_path.parent.mkdir(parents=True, exist_ok=True)

        log.info("Initializing MemoryManager with storage at %s", cfg.db_path)

        # ------------------------------------------------------------------
        # Smart Memory Part 1 — always on
        # ------------------------------------------------------------------
        self.profile = UserProfile(cfg.db_path)
        self.preferences = PreferencesManager(cfg.db_path)
        self.facts = FactsManager(cfg.db_path)
        self.contacts = ContactsManager(cfg.db_path)

        # ------------------------------------------------------------------
        # Smart Memory Part 2 — config-gated
        # ------------------------------------------------------------------
        self.projects: Optional[ProjectMemory] = None
        self.locations: Optional[LocationMemory] = None
        self.reminders: Optional[ReminderMemory] = None
        self.calendar: Optional[CalendarMemory] = None
        self.conversation_summary: Optional[ConversationSummary] = None

        if _cfg(cfg, "project_memory_enabled", True):
            self.projects = ProjectMemory(
                _cfg(cfg, "project_memory_db_path", "data/project_memory.sqlite")
            )
            log.info("Project memory enabled")

        if _cfg(cfg, "location_memory_enabled", True):
            self.locations = LocationMemory(
                _cfg(cfg, "location_memory_db_path", "data/location_memory.sqlite")
            )
            log.info("Location memory enabled")

        if _cfg(cfg, "reminder_memory_enabled", True):
            self.reminders = ReminderMemory(
                _cfg(cfg, "reminder_memory_db_path", "data/reminder_memory.sqlite")
            )
            log.info("Reminder memory enabled")

        if _cfg(cfg, "calendar_memory_enabled", True):
            self.calendar = CalendarMemory(
                _cfg(cfg, "calendar_memory_db_path", "data/calendar_memory.sqlite")
            )
            log.info("Calendar memory enabled")

        if _cfg(cfg, "conversation_summary_enabled", True):
            self.conversation_summary = ConversationSummary(
                _cfg(
                    cfg,
                    "conversation_summary_db_path",
                    "data/conversation_summary.sqlite",
                ),
                trigger_messages=_cfg(cfg, "summary_trigger_messages", 30),
                max_length=_cfg(cfg, "summary_max_length", 500),
            )
            log.info("Conversation summary enabled")

        # ------------------------------------------------------------------
        # Smart Memory Part 3 — importance + ranking + semantic search
        # ------------------------------------------------------------------
        self.importance_scorer = MemoryImportanceScorer(
            enabled=bool(_cfg(cfg, "importance_scoring_enabled", True))
        )
        self.embedding_manager: Optional[EmbeddingManager] = None
        self.vector_store: Optional[VectorStore] = None
        self.memory_ranker: Optional[MemoryRanker] = None
        self.semantic_search_engine: Optional[SemanticSearchEngine] = None

        if _cfg(cfg, "semantic_search_enabled", True) and _cfg(
            cfg, "vector_store_enabled", True
        ):
            self.embedding_manager = EmbeddingManager(
                dimensions=_cfg(cfg, "embedding_dimensions", 256),
                cache_size=_cfg(cfg, "embedding_cache_size", 1024),
            )
            self.vector_store = VectorStore(
                _cfg(cfg, "vector_store_db_path", "data/memory_vectors.sqlite")
            )
            self.memory_ranker = MemoryRanker(
                similarity_weight=float(_cfg(cfg, "ranking_similarity_bias", 0.55)),
                importance_weight=float(_cfg(cfg, "ranking_importance_bias", 0.30)),
                recency_weight=float(_cfg(cfg, "ranking_recency_bias", 0.15)),
            )
            self.semantic_search_engine = SemanticSearchEngine(
                self.embedding_manager,
                self.vector_store,
                ranker=self.memory_ranker,
                namespace="memory",
                default_limit=int(_cfg(cfg, "semantic_search_default_limit", 5)),
                min_similarity=float(_cfg(cfg, "semantic_search_min_similarity", 0.12)),
            )
            if _cfg(cfg, "semantic_search_rebuild_on_start", True):
                try:
                    indexed = self.rebuild_semantic_index()
                    log.info("Semantic index rebuilt with %d records", indexed)
                except Exception:  # pragma: no cover - defensive
                    log.warning("Semantic index rebuild failed", exc_info=True)

        log.info("MemoryManager initialized successfully")

    # ==================================================================
    # Internal helpers — Part 3 semantic indexing
    # ==================================================================

    def _semantic_enabled(self) -> bool:
        return self.semantic_search_engine is not None

    def _score_importance(
        self,
        text: str,
        *,
        category: str = "general",
        source_type: str = "general",
        metadata: Optional[Dict[str, Any]] = None,
        explicit_importance: Optional[int] = None,
    ) -> Dict[str, Any]:
        return self.importance_scorer.score(
            text,
            category=category,
            source_type=source_type,
            metadata=metadata,
            explicit_importance=explicit_importance,
        )

    def _upsert_semantic_memory(
        self,
        record_id: str,
        text: str,
        *,
        source_type: str,
        source_id: Optional[str] = None,
        category: str = "general",
        metadata: Optional[Dict[str, Any]] = None,
        explicit_importance: Optional[int] = None,
    ) -> Optional[str]:
        if not self.semantic_search_engine or not text:
            return None
        meta = dict(metadata or {})
        meta.setdefault("category", category)
        score_info = self._score_importance(
            text,
            category=category,
            source_type=source_type,
            metadata=meta,
            explicit_importance=explicit_importance,
        )
        meta["importance_level"] = score_info["level"]
        meta["importance_reasons"] = score_info["reasons"]
        return self.semantic_search_engine.index_memory(
            record_id,
            text,
            source_type=source_type,
            source_id=source_id,
            metadata=meta,
            importance=score_info["score"],
        )

    def _delete_semantic_memory(self, record_id: str) -> bool:
        if not self.semantic_search_engine:
            return False
        return self.semantic_search_engine.delete_memory(record_id)

    def _find_fact_row(self, key: str) -> Optional[Dict[str, Any]]:
        key_lower = (key or "").lower()
        for item in self.facts.search(key_lower):
            if item.get("key") == key_lower:
                return item
        return None

    def _sync_profile_index(self) -> None:
        if not self._semantic_enabled():
            return
        important = {
            "name": 5,
            "nickname": 4,
            "language": 4,
            "timezone": 4,
            "preferred_assistant_name": 5,
            "bio": 3,
        }
        for field, value in self.get_profile().items():
            record_id = f"profile:{field}"
            if value in (None, "", [], {}):
                self._delete_semantic_memory(record_id)
                continue
            text = f"User profile {field.replace('_', ' ')}: {value}"
            self._upsert_semantic_memory(
                record_id,
                text,
                source_type="profile",
                source_id=field,
                category="profile",
                metadata={"field": field, "value": value},
                explicit_importance=important.get(field, 3),
            )

    def _sync_preferences_index(self) -> None:
        if not self._semantic_enabled():
            return
        for key, value in self.get_all_preferences().items():
            if value in (None, "", [], {}):
                self._delete_semantic_memory(f"preference:{key}")
                continue
            text = f"User preference {key.replace('_', ' ')}: {value}"
            self._upsert_semantic_memory(
                f"preference:{key}",
                text,
                source_type="preference",
                source_id=key,
                category="preference",
                metadata={"key": key, "value": value},
                explicit_importance=3,
            )

    def _sync_fact_index(self, key: str) -> None:
        row = self._find_fact_row(key)
        record_id = f"fact:{(key or '').lower()}"
        if not row:
            self._delete_semantic_memory(record_id)
            return
        text = f"Fact {row['key']}: {row['value']}. Category: {row.get('category', 'general')}"
        self._upsert_semantic_memory(
            record_id,
            text,
            source_type="fact",
            source_id=row["key"],
            category=row.get("category", "general"),
            metadata=row,
            explicit_importance=row.get("importance"),
        )

    def _sync_contact_index(self, contact_id: str) -> None:
        contact = self.contacts.get_by_id(contact_id)
        record_id = f"contact:{contact_id}"
        if not contact:
            self._delete_semantic_memory(record_id)
            return
        parts = [f"Contact {contact.get('name', '')}"]
        if contact.get("nickname"):
            parts.append(f"nickname {contact['nickname']}")
        if contact.get("relationship"):
            parts.append(f"relationship {contact['relationship']}")
        if contact.get("phone"):
            parts.append(f"phone {contact['phone']}")
        if contact.get("email"):
            parts.append(f"email {contact['email']}")
        if contact.get("notes"):
            parts.append(f"notes {contact['notes']}")
        text = ". ".join(parts)
        self._upsert_semantic_memory(
            record_id,
            text,
            source_type="contact",
            source_id=contact_id,
            category="contact",
            metadata=contact,
            explicit_importance=4,
        )

    def _sync_project_index(self, project_id: str) -> None:
        if not self.projects:
            return
        project = self.projects.get(project_id)
        record_id = f"project:{project_id}"
        if not project:
            self._delete_semantic_memory(record_id)
            return
        text = (
            f"Project {project.get('name', '')}. "
            f"Status: {project.get('status', 'active')}. "
            f"Description: {project.get('description') or ''}. "
            f"Goals: {', '.join(project.get('goals', []))}. "
            f"Tags: {', '.join(project.get('tags', []))}. "
            f"Files: {', '.join(project.get('related_files', []))}."
        )
        self._upsert_semantic_memory(
            record_id,
            text,
            source_type="project",
            source_id=project_id,
            category="project",
            metadata=project,
            explicit_importance=4,
        )

    def _sync_location_index(self, label: str) -> None:
        if not self.locations:
            return
        location = self.locations.get_location(label)
        record_id = f"location:{(label or '').lower()}"
        if not location:
            self._delete_semantic_memory(record_id)
            return
        text = (
            f"Location {location.get('display_name') or location.get('label', '')}. "
            f"Address: {location.get('address') or ''}. "
            f"Category: {location.get('category', 'custom')}. "
            f"Notes: {location.get('notes') or ''}."
        )
        self._upsert_semantic_memory(
            record_id,
            text,
            source_type="location",
            source_id=location.get("id"),
            category="location",
            metadata=location,
            explicit_importance=(
                4 if location.get("category") in {"home", "office"} else 3
            ),
        )

    def _sync_reminder_index(self, reminder_id: str) -> None:
        if not self.reminders:
            return
        reminder = self.reminders.get(reminder_id)
        record_id = f"reminder:{reminder_id}"
        if not reminder:
            self._delete_semantic_memory(record_id)
            return
        text = (
            f"Reminder {reminder.get('title', '')}. "
            f"Description: {reminder.get('description') or ''}. "
            f"Due: {reminder.get('due_at') or 'unscheduled'}. "
            f"Recurrence: {reminder.get('recurrence') or 'none'}."
        )
        self._upsert_semantic_memory(
            record_id,
            text,
            source_type="reminder",
            source_id=reminder_id,
            category="reminder",
            metadata=reminder,
            explicit_importance=reminder.get("priority", 3),
        )

    def _sync_calendar_index(self, event_id: str) -> None:
        if not self.calendar:
            return
        event = self.calendar.get(event_id)
        record_id = f"calendar:{event_id}"
        if not event:
            self._delete_semantic_memory(record_id)
            return
        text = (
            f"Calendar event {event.get('title', '')}. "
            f"Type: {event.get('event_type', 'event')}. "
            f"Start: {event.get('start_at') or ''}. End: {event.get('end_at') or ''}. "
            f"Location: {event.get('location') or ''}. Description: {event.get('description') or ''}."
        )
        self._upsert_semantic_memory(
            record_id,
            text,
            source_type="calendar",
            source_id=event_id,
            category="calendar",
            metadata=event,
            explicit_importance=4,
        )

    def _sync_conversation_summary_index(self, conversation_id: str) -> None:
        if not self.conversation_summary:
            return
        summary = self.conversation_summary.latest_summary(conversation_id)
        record_id = f"conversation_summary:{conversation_id}"
        if not summary or not summary.get("summary"):
            self._delete_semantic_memory(record_id)
            return
        text = f"Conversation summary for {conversation_id}: {summary['summary']}"
        self._upsert_semantic_memory(
            record_id,
            text,
            source_type="conversation_summary",
            source_id=conversation_id,
            category="summary",
            metadata=summary,
            explicit_importance=3,
        )

    def _iter_semantic_records(self) -> Iterable[Dict[str, Any]]:
        important_profile = {
            "name": 5,
            "nickname": 4,
            "language": 4,
            "timezone": 4,
            "preferred_assistant_name": 5,
            "bio": 3,
        }
        profile = self.get_profile()
        for field, value in profile.items():
            if value in (None, "", [], {}):
                continue
            text = f"User profile {field.replace('_', ' ')}: {value}"
            score = self._score_importance(
                text,
                category="profile",
                source_type="profile",
                metadata={"field": field, "value": value},
                explicit_importance=important_profile.get(field, 3),
            )
            yield {
                "record_id": f"profile:{field}",
                "text": text,
                "source_type": "profile",
                "source_id": field,
                "metadata": {
                    "field": field,
                    "value": value,
                    "category": "profile",
                    "importance_level": score["level"],
                    "importance_reasons": score["reasons"],
                },
                "importance": score["score"],
            }

        for key, value in self.get_all_preferences().items():
            if value in (None, "", [], {}):
                continue
            text = f"User preference {key.replace('_', ' ')}: {value}"
            score = self._score_importance(
                text,
                category="preference",
                source_type="preference",
                metadata={"key": key, "value": value},
                explicit_importance=3,
            )
            yield {
                "record_id": f"preference:{key}",
                "text": text,
                "source_type": "preference",
                "source_id": key,
                "metadata": {
                    "key": key,
                    "value": value,
                    "category": "preference",
                    "importance_level": score["level"],
                    "importance_reasons": score["reasons"],
                },
                "importance": score["score"],
            }

        for fact in self.get_all_facts():
            text = f"Fact {fact['key']}: {fact['value']}. Category: {fact.get('category', 'general')}"
            score = self._score_importance(
                text,
                category=fact.get("category", "general"),
                source_type="fact",
                metadata=fact,
                explicit_importance=fact.get("importance"),
            )
            yield {
                "record_id": f"fact:{fact['key']}",
                "text": text,
                "source_type": "fact",
                "source_id": fact["key"],
                "metadata": {
                    **fact,
                    "importance_level": score["level"],
                    "importance_reasons": score["reasons"],
                },
                "importance": score["score"],
            }

        for contact in self.get_all_contacts():
            parts = [f"Contact {contact.get('name', '')}"]
            if contact.get("nickname"):
                parts.append(f"nickname {contact['nickname']}")
            if contact.get("relationship"):
                parts.append(f"relationship {contact['relationship']}")
            if contact.get("phone"):
                parts.append(f"phone {contact['phone']}")
            if contact.get("email"):
                parts.append(f"email {contact['email']}")
            if contact.get("notes"):
                parts.append(f"notes {contact['notes']}")
            text = ". ".join(parts)
            score = self._score_importance(
                text,
                category="contact",
                source_type="contact",
                metadata=contact,
                explicit_importance=4,
            )
            yield {
                "record_id": f"contact:{contact['id']}",
                "text": text,
                "source_type": "contact",
                "source_id": contact["id"],
                "metadata": {
                    **contact,
                    "category": "contact",
                    "importance_level": score["level"],
                    "importance_reasons": score["reasons"],
                },
                "importance": score["score"],
            }

        if self.projects:
            for project in self.projects.list_projects(include_archived=True):
                text = (
                    f"Project {project.get('name', '')}. Status: {project.get('status', 'active')}. "
                    f"Description: {project.get('description') or ''}. Goals: {', '.join(project.get('goals', []))}. "
                    f"Tags: {', '.join(project.get('tags', []))}. Files: {', '.join(project.get('related_files', []))}."
                )
                score = self._score_importance(
                    text,
                    category="project",
                    source_type="project",
                    metadata=project,
                    explicit_importance=4,
                )
                yield {
                    "record_id": f"project:{project['id']}",
                    "text": text,
                    "source_type": "project",
                    "source_id": project["id"],
                    "metadata": {
                        **project,
                        "category": "project",
                        "importance_level": score["level"],
                        "importance_reasons": score["reasons"],
                    },
                    "importance": score["score"],
                }

        if self.locations:
            for location in self.locations.list_locations():
                text = (
                    f"Location {location.get('display_name') or location.get('label', '')}. "
                    f"Address: {location.get('address') or ''}. Category: {location.get('category', 'custom')}. "
                    f"Notes: {location.get('notes') or ''}."
                )
                score = self._score_importance(
                    text,
                    category="location",
                    source_type="location",
                    metadata=location,
                    explicit_importance=(
                        4 if location.get("category") in {"home", "office"} else 3
                    ),
                )
                yield {
                    "record_id": f"location:{location.get('label', '')}",
                    "text": text,
                    "source_type": "location",
                    "source_id": location.get("id"),
                    "metadata": {
                        **location,
                        "category": "location",
                        "importance_level": score["level"],
                        "importance_reasons": score["reasons"],
                    },
                    "importance": score["score"],
                }

        if self.reminders:
            for reminder in self.reminders.list_all():
                text = (
                    f"Reminder {reminder.get('title', '')}. Description: {reminder.get('description') or ''}. "
                    f"Due: {reminder.get('due_at') or 'unscheduled'}. Recurrence: {reminder.get('recurrence') or 'none'}."
                )
                score = self._score_importance(
                    text,
                    category="reminder",
                    source_type="reminder",
                    metadata=reminder,
                    explicit_importance=reminder.get("priority", 3),
                )
                yield {
                    "record_id": f"reminder:{reminder['id']}",
                    "text": text,
                    "source_type": "reminder",
                    "source_id": reminder["id"],
                    "metadata": {
                        **reminder,
                        "category": "reminder",
                        "importance_level": score["level"],
                        "importance_reasons": score["reasons"],
                    },
                    "importance": score["score"],
                }

        if self.calendar:
            for event in self.calendar.list_events():
                text = (
                    f"Calendar event {event.get('title', '')}. Type: {event.get('event_type', 'event')}. "
                    f"Start: {event.get('start_at') or ''}. End: {event.get('end_at') or ''}. "
                    f"Location: {event.get('location') or ''}. Description: {event.get('description') or ''}."
                )
                score = self._score_importance(
                    text,
                    category="calendar",
                    source_type="calendar",
                    metadata=event,
                    explicit_importance=4,
                )
                yield {
                    "record_id": f"calendar:{event['id']}",
                    "text": text,
                    "source_type": "calendar",
                    "source_id": event["id"],
                    "metadata": {
                        **event,
                        "category": "calendar",
                        "importance_level": score["level"],
                        "importance_reasons": score["reasons"],
                    },
                    "importance": score["score"],
                }

        if self.conversation_summary:
            for item in self.conversation_summary.get_summaries(limit=1000):
                if not item.get("summary"):
                    continue
                text = f"Conversation summary for {item.get('conversation_id', 'default')}: {item['summary']}"
                score = self._score_importance(
                    text,
                    category="summary",
                    source_type="conversation_summary",
                    metadata=item,
                    explicit_importance=3,
                )
                yield {
                    "record_id": f"conversation_summary:{item.get('conversation_id', 'default')}",
                    "text": text,
                    "source_type": "conversation_summary",
                    "source_id": item.get("conversation_id", "default"),
                    "metadata": {
                        **item,
                        "category": "summary",
                        "importance_level": score["level"],
                        "importance_reasons": score["reasons"],
                    },
                    "importance": score["score"],
                }

    # ==================================================================
    # Smart Memory Part 1 — Profile Access (unchanged API)
    # ==================================================================

    def get_user_name(self) -> Optional[str]:
        return self.profile.get_name()

    def set_user_name(self, name: str) -> None:
        self.profile.set_name(name)
        self._sync_profile_index()
        log.info("User name set to: %s", name)

    def get_user_timezone(self) -> Optional[str]:
        return self.profile.get_timezone()

    def set_user_timezone(self, tz: str) -> None:
        self.profile.set_timezone(tz)
        self._sync_profile_index()
        log.info("User timezone set to: %s", tz)

    def get_user_language(self) -> str:
        return self.profile.get_language()

    def set_user_language(self, lang: str) -> None:
        self.profile.set_language(lang)
        self._sync_profile_index()
        log.info("User language set to: %s", lang)

    def get_assistant_name(self) -> Optional[str]:
        return self.profile.get_preferred_assistant_name()

    def set_assistant_name(self, name: str) -> None:
        self.profile.set_preferred_assistant_name(name)
        self._sync_profile_index()
        log.info("Preferred assistant name set to: %s", name)

    def get_profile(self) -> Dict[str, Any]:
        return self.profile.get_all()

    def update_profile(self, data: Dict[str, Any]) -> None:
        self.profile.update_multiple(data)
        self._sync_profile_index()
        log.info("Profile updated with %d fields", len(data))

    # ==================================================================
    # Smart Memory Part 1 — Preferences (unchanged API)
    # ==================================================================

    def get_preference(self, key: str, default: Any = None) -> Any:
        return self.preferences.get(key, default)

    def set_preference(self, key: str, value: Any) -> None:
        self.preferences.set(key, value)
        self._sync_preferences_index()
        log.debug("Preference %s = %s", key, value)

    def get_all_preferences(self) -> Dict[str, Any]:
        return self.preferences.get_all()

    def update_preferences(self, data: Dict[str, Any]) -> None:
        self.preferences.update_multiple(data)
        self._sync_preferences_index()
        log.info("Preferences updated: %s", list(data.keys()))

    # ==================================================================
    # Smart Memory Part 1 — Facts (unchanged API)
    # ==================================================================

    def remember_fact(
        self, key: str, value: str, category: str = "general", importance: int = 1
    ) -> None:
        self.facts.remember(key, value, category, importance)
        self._sync_fact_index(key)
        log.debug("Fact remembered: %s = %s", key, value)

    def recall_fact(self, key: str) -> Optional[str]:
        return self.facts.recall(key)

    def get_facts_by_category(self, category: str) -> Dict[str, str]:
        return self.facts.get_by_category(category)

    def get_all_facts(self) -> List[Dict[str, Any]]:
        return self.facts.get_all()

    def update_fact(self, key: str, value: str, importance: int = 1) -> None:
        self.facts.update(key, value, importance)
        self._sync_fact_index(key)
        log.debug("Fact updated: %s", key)

    def forget_fact(self, key: str) -> None:
        self.facts.forget(key)
        self._delete_semantic_memory(f"fact:{(key or '').lower()}")
        log.info("Fact removed: %s", key)

    # ==================================================================
    # Smart Memory Part 1 — Contacts (unchanged API)
    # ==================================================================

    def add_contact(
        self,
        name: str,
        phone: Optional[str] = None,
        email: Optional[str] = None,
        relationship: Optional[str] = None,
        nickname: Optional[str] = None,
    ) -> str:
        contact_id = self.contacts.add(name, phone, email, relationship, nickname)
        self._sync_contact_index(contact_id)
        log.info("Contact added: %s (ID: %s)", name, contact_id)
        return contact_id

    def get_contact(self, contact_id: str) -> Optional[Dict[str, Any]]:
        return self.contacts.get_by_id(contact_id)

    def search_contacts(self, query: str) -> List[Dict[str, Any]]:
        return self.contacts.search(query)

    def update_contact(self, contact_id: str, **kwargs: Any) -> None:
        self.contacts.update(contact_id, **kwargs)
        self._sync_contact_index(contact_id)
        log.info("Contact updated: %s", contact_id)

    def delete_contact(self, contact_id: str) -> None:
        self.contacts.delete(contact_id)
        self._delete_semantic_memory(f"contact:{contact_id}")
        log.info("Contact deleted: %s", contact_id)

    def get_all_contacts(self) -> List[Dict[str, Any]]:
        return self.contacts.get_all()

    # ==================================================================
    # Smart Memory Part 2 — Projects
    # ==================================================================

    def add_project(self, name: str, **kwargs: Any) -> Optional[str]:
        if not self.projects:
            return None
        project_id = self.projects.add_project(name, **kwargs)
        self._sync_project_index(project_id)
        return project_id

    def get_project(self, project_id: str) -> Optional[Dict[str, Any]]:
        return self.projects.get(project_id) if self.projects else None

    def get_project_by_name(self, name: str) -> Optional[Dict[str, Any]]:
        return self.projects.get_by_name(name) if self.projects else None

    def update_project(self, project_id: str, **fields: Any) -> bool:
        ok = (
            self.projects.update_project(project_id, **fields)
            if self.projects
            else False
        )
        if ok:
            self._sync_project_index(project_id)
        return ok

    def archive_project(self, project_id: str) -> bool:
        ok = self.projects.archive_project(project_id) if self.projects else False
        if ok:
            self._sync_project_index(project_id)
        return ok

    def delete_project(self, project_id: str) -> bool:
        ok = self.projects.delete_project(project_id) if self.projects else False
        if ok:
            self._delete_semantic_memory(f"project:{project_id}")
        return ok

    def list_projects(self, **kwargs: Any) -> List[Dict[str, Any]]:
        return self.projects.list_projects(**kwargs) if self.projects else []

    def search_projects(self, query: str) -> List[Dict[str, Any]]:
        return self.projects.search(query) if self.projects else []

    def link_conversation_to_project(
        self, project_id: str, conversation_id: str
    ) -> bool:
        if not self.projects:
            return False
        return self.projects.link_conversation(project_id, conversation_id)

    def auto_link_projects(self, text: str, conversation_id: str) -> List[str]:
        if not self.projects:
            return []
        return self.projects.auto_link_from_text(text, conversation_id)

    # ==================================================================
    # Smart Memory Part 2 — Locations
    # ==================================================================

    def save_location(self, label: str, **kwargs: Any) -> Optional[str]:
        if not self.locations:
            return None
        location_id = self.locations.save_location(label, **kwargs)
        self._sync_location_index(label)
        return location_id

    def get_location(self, label: str) -> Optional[Dict[str, Any]]:
        return self.locations.get_location(label) if self.locations else None

    def set_home(self, **kwargs: Any) -> Optional[str]:
        if not self.locations:
            return None
        location_id = self.locations.set_home(**kwargs)
        self._sync_location_index("home")
        return location_id

    def set_office(self, **kwargs: Any) -> Optional[str]:
        if not self.locations:
            return None
        location_id = self.locations.set_office(**kwargs)
        self._sync_location_index("office")
        return location_id

    def get_home(self) -> Optional[Dict[str, Any]]:
        return self.locations.get_home() if self.locations else None

    def get_office(self) -> Optional[Dict[str, Any]]:
        return self.locations.get_office() if self.locations else None

    def search_locations(self, query: str) -> List[Dict[str, Any]]:
        return self.locations.search(query) if self.locations else []

    def list_locations(self, **kwargs: Any) -> List[Dict[str, Any]]:
        return self.locations.list_locations(**kwargs) if self.locations else []

    def delete_location(self, label: str) -> bool:
        ok = self.locations.delete_location(label) if self.locations else False
        if ok:
            self._delete_semantic_memory(f"location:{(label or '').lower()}")
        return ok

    # ==================================================================
    # Smart Memory Part 2 — Reminders
    # ==================================================================

    def add_reminder(self, title: str, **kwargs: Any) -> Optional[str]:
        if not self.reminders:
            return None
        reminder_id = self.reminders.add_reminder(title, **kwargs)
        self._sync_reminder_index(reminder_id)
        return reminder_id

    def update_reminder(self, reminder_id: str, **fields: Any) -> bool:
        ok = (
            self.reminders.update_reminder(reminder_id, **fields)
            if self.reminders
            else False
        )
        if ok:
            self._sync_reminder_index(reminder_id)
        return ok

    def delete_reminder(self, reminder_id: str) -> bool:
        ok = self.reminders.delete_reminder(reminder_id) if self.reminders else False
        if ok:
            self._delete_semantic_memory(f"reminder:{reminder_id}")
        return ok

    def complete_reminder(self, reminder_id: str) -> bool:
        ok = self.reminders.mark_completed(reminder_id) if self.reminders else False
        if ok:
            self._sync_reminder_index(reminder_id)
        return ok

    def list_reminders(self, include_completed: bool = False) -> List[Dict[str, Any]]:
        if not self.reminders:
            return []
        return (
            self.reminders.list_all()
            if include_completed
            else self.reminders.list_pending()
        )

    def overdue_reminders(self) -> List[Dict[str, Any]]:
        return self.reminders.overdue() if self.reminders else []

    # ==================================================================
    # Smart Memory Part 2 — Calendar
    # ==================================================================

    def add_calendar_event(self, title: str, **kwargs: Any) -> Optional[str]:
        if not self.calendar:
            return None
        event_id = self.calendar.add_event(title, **kwargs)
        self._sync_calendar_index(event_id)
        return event_id

    def add_birthday(self, name: str, date: Any, **kwargs: Any) -> Optional[str]:
        if not self.calendar:
            return None
        event_id = self.calendar.add_birthday(name, date, **kwargs)
        self._sync_calendar_index(event_id)
        return event_id

    def add_meeting(
        self, title: str, start_at: Any, end_at: Any = None, **kwargs: Any
    ) -> Optional[str]:
        if not self.calendar:
            return None
        event_id = self.calendar.add_meeting(title, start_at, end_at, **kwargs)
        self._sync_calendar_index(event_id)
        return event_id

    def update_calendar_event(self, event_id: str, **fields: Any) -> bool:
        ok = self.calendar.update_event(event_id, **fields) if self.calendar else False
        if ok:
            self._sync_calendar_index(event_id)
        return ok

    def delete_calendar_event(self, event_id: str) -> bool:
        ok = self.calendar.delete_event(event_id) if self.calendar else False
        if ok:
            self._delete_semantic_memory(f"calendar:{event_id}")
        return ok

    def upcoming_events(self, days: int = 7) -> List[Dict[str, Any]]:
        return self.calendar.upcoming(days=days) if self.calendar else []

    def events_on(self, date: Any) -> List[Dict[str, Any]]:
        return self.calendar.events_on(date) if self.calendar else []

    def search_calendar(self, query: str) -> List[Dict[str, Any]]:
        return self.calendar.search(query) if self.calendar else []

    # ==================================================================
    # Smart Memory Part 2 — Conversation Summary
    # ==================================================================

    def record_message(
        self, role: str, content: str, conversation_id: str = "default"
    ) -> Optional[str]:
        """Record a conversation message and auto-summarize when needed.

        Also auto-links the message text to known projects. Returns the new
        summary id if one was generated.
        """
        summary_id: Optional[str] = None
        if self.conversation_summary:
            summary_id = self.conversation_summary.add_message(
                role, content, conversation_id
            )
        if self.projects and content:
            try:
                self.projects.auto_link_from_text(content, conversation_id)
            except Exception:  # pragma: no cover - defensive
                log.debug("auto_link_from_text failed", exc_info=True)
        if summary_id:
            self._sync_conversation_summary_index(conversation_id)
        return summary_id

    def summarize_conversation(self, conversation_id: str = "default") -> Optional[str]:
        if not self.conversation_summary:
            return None
        summary_id = self.conversation_summary.summarize_now(conversation_id)
        if summary_id:
            self._sync_conversation_summary_index(conversation_id)
        return summary_id

    def get_conversation_summary(
        self, conversation_id: str = "default"
    ) -> Optional[Dict[str, Any]]:
        if not self.conversation_summary:
            return None
        return self.conversation_summary.latest_summary(conversation_id)

    def get_conversation_context(
        self, conversation_id: str = "default", recent_messages: int = 10
    ) -> Dict[str, Any]:
        if not self.conversation_summary:
            return {"summary": "", "recent": []}
        return self.conversation_summary.get_context(conversation_id, recent_messages)

    def set_conversation_summarizer(self, fn: Any) -> None:
        if self.conversation_summary:
            self.conversation_summary.set_summarizer(fn)

    # ==================================================================
    # Smart Memory Part 3 — new APIs
    # ==================================================================

    def score_memory_importance(
        self,
        text: str,
        *,
        category: str = "general",
        source_type: str = "general",
        metadata: Optional[Dict[str, Any]] = None,
        explicit_importance: Optional[int] = None,
    ) -> Dict[str, Any]:
        return self._score_importance(
            text,
            category=category,
            source_type=source_type,
            metadata=metadata,
            explicit_importance=explicit_importance,
        )

    def rank_memory_results(
        self,
        items: List[Dict[str, Any]],
        query: str = "",
        limit: Optional[int] = None,
    ) -> List[Dict[str, Any]]:
        if not self.memory_ranker:
            return items[:limit] if limit else items
        return self.memory_ranker.rank(items, query=query, top_k=limit)

    def semantic_search_memories(
        self,
        query: str,
        limit: Optional[int] = None,
        source_types: Optional[List[str]] = None,
        min_similarity: Optional[float] = None,
    ) -> List[Dict[str, Any]]:
        if not self.semantic_search_engine:
            return []
        return self.semantic_search_engine.search(
            query,
            limit=limit,
            source_types=source_types,
            min_similarity=min_similarity,
        )

    def rebuild_semantic_index(self) -> int:
        if not self.semantic_search_engine:
            return 0
        return self.semantic_search_engine.rebuild(list(self._iter_semantic_records()))

    def get_semantic_index_stats(self) -> Dict[str, Any]:
        if not self.vector_store:
            return {"enabled": False, "count": 0, "by_source_type": {}}
        stats = self.vector_store.stats(namespace="memory")
        stats["enabled"] = True
        return stats

    # ==================================================================
    # Summary and Statistics
    # ==================================================================

    def get_summary(self) -> Dict[str, Any]:
        semantic_stats = self.get_semantic_index_stats()
        return {
            "profile": self.get_profile(),
            "preferences": self.get_all_preferences(),
            "facts_count": len(self.get_all_facts()),
            "contacts_count": len(self.get_all_contacts()),
            "projects_count": (
                len(self.list_projects(include_archived=True)) if self.projects else 0
            ),
            "locations_count": len(self.list_locations()) if self.locations else 0,
            "reminders_count": (
                len(self.list_reminders(include_completed=True))
                if self.reminders
                else 0
            ),
            "calendar_events_count": (
                self.calendar.stats()["total"] if self.calendar else 0
            ),
            "conversation_summaries_count": (
                self.conversation_summary.stats()["summaries"]
                if self.conversation_summary
                else 0
            ),
            "semantic_index_count": semantic_stats.get("count", 0),
        }

    def get_statistics(self) -> Dict[str, Any]:
        facts = self.get_all_facts()
        facts_by_category: Dict[str, int] = {}
        for fact in facts:
            cat = fact.get("category", "general")
            facts_by_category[cat] = facts_by_category.get(cat, 0) + 1

        stats: Dict[str, Any] = {
            "total_facts": len(facts),
            "facts_by_category": facts_by_category,
            "total_contacts": len(self.get_all_contacts()),
            "profile_complete": bool(self.get_user_name()),
            "semantic_index": self.get_semantic_index_stats(),
        }
        if self.projects:
            stats["projects"] = self.projects.stats()
        if self.locations:
            stats["locations"] = self.locations.stats()
        if self.reminders:
            stats["reminders"] = self.reminders.stats()
        if self.calendar:
            stats["calendar"] = self.calendar.stats()
        if self.conversation_summary:
            stats["conversation_summary"] = self.conversation_summary.stats()
        return stats

    def export_memory(self, export_path: str) -> None:
        export_data: Dict[str, Any] = {
            "exported_at": datetime.now().isoformat(),
            "profile": self.get_profile(),
            "preferences": self.get_all_preferences(),
            "facts": self.get_all_facts(),
            "contacts": self.get_all_contacts(),
            "statistics": self.get_statistics(),
        }
        if self.projects:
            export_data["projects"] = self.projects.list_projects(include_archived=True)
        if self.locations:
            export_data["locations"] = self.locations.list_locations()
        if self.reminders:
            export_data["reminders"] = self.reminders.list_all()
        if self.calendar:
            export_data["calendar_events"] = self.calendar.list_events()
        if self.conversation_summary:
            export_data["conversation_summaries"] = (
                self.conversation_summary.get_summaries(limit=1000)
            )
        if self.vector_store:
            export_data["semantic_index"] = self.vector_store.list_records(
                namespace="memory"
            )
        Path(export_path).write_text(json.dumps(export_data, indent=2, default=str))
        log.info("Memory exported to %s", export_path)

    def clear_all(self, confirm: bool = False) -> None:
        if not confirm:
            log.warning("Clear all requires confirm=True")
            return
        self.profile.clear()
        self.preferences.clear()
        self.facts.clear()
        self.contacts.clear()
        if self.projects:
            self.projects.clear()
        if self.locations:
            self.locations.clear()
        if self.reminders:
            self.reminders.clear()
        if self.calendar:
            self.calendar.clear()
        if self.conversation_summary:
            self.conversation_summary.clear()
        if self.vector_store:
            self.vector_store.clear_namespace("memory")
        log.warning("All memory cleared!")
