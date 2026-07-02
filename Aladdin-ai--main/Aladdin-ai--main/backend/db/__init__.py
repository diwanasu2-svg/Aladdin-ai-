"""
Database package — schema verification and migration utilities.
Task 39: schema_verification.py performs startup DB schema checks.
"""
from .schema_verification import (
    verify_all_schemas,
    run_startup_schema_check,
    verify_db,
    attempt_repair,
    EXPECTED_SCHEMAS,
)

__all__ = [
    "verify_all_schemas",
    "run_startup_schema_check",
    "verify_db",
    "attempt_repair",
    "EXPECTED_SCHEMAS",
]
