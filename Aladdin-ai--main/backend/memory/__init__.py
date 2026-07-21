"""
Memory subsystem for Aladdin AI Backend.
Task 28: run_all_memory_migrations() invoked at import time to ensure
         schema is up-to-date before any memory DB is first opened.
"""
import logging
import os
from pathlib import Path

logger = logging.getLogger(__name__)

# Task 28: Run migrations before importing any memory class
_DATA_DIR = Path(os.getenv("DATA_DIR", "data"))
try:
    from .migration import run_all_memory_migrations
    run_all_memory_migrations(_DATA_DIR)
except Exception as _exc:
    logger.warning("Memory migrations step failed (non-fatal): %s", _exc)

from .short_term import ShortTermMemory
from .long_term import LongTermMemory
from .semantic import SemanticMemory
from .contacts import ContactsMemory
from .profile import ProfileMemory
from .preferences import PreferencesMemory
from .projects import ProjectsMemory
from .locations import LocationsMemory

__all__ = [
    "ShortTermMemory", "LongTermMemory", "SemanticMemory",
    "ContactsMemory", "ProfileMemory", "PreferencesMemory",
    "ProjectsMemory", "LocationsMemory",
    "run_all_memory_migrations",
]

