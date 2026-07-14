"""Base repository primitives for PostgreSQL scientific memory access."""

from __future__ import annotations

from typing import Any, Mapping, Sequence

from database.postgres.database import Database, Parameters


class BaseRepository:
    """Small shared data-access wrapper for domain repositories.

    Future repositories (ReactionRepository, PaperRepository,
    ExperimentRepository, PredictionRepository, etc.) should inherit from this
    class so they all share the same Database connection lifecycle and query
    behavior.
    """

    def __init__(self, database: Database) -> None:
        self.database = database

    def execute(self, query: str, params: Parameters = None) -> int:
        """Execute a write statement and return affected row count."""
        return self.database.execute(query, params)

    def fetch_one(self, query: str, params: Parameters = None) -> dict[str, Any] | None:
        """Fetch one row from the scientific memory database."""
        return self.database.fetch_one(query, params)

    def fetch_all(self, query: str, params: Parameters = None) -> list[dict[str, Any]]:
        """Fetch all rows from the scientific memory database."""
        return self.database.fetch_all(query, params)

    @staticmethod
    def columns(record: Mapping[str, Any]) -> str:
        """Return a comma-separated SQL column list for insert helpers."""
        return ", ".join(record.keys())

    @staticmethod
    def placeholders(record: Mapping[str, Any]) -> str:
        """Return psycopg named placeholders for each record key."""
        return ", ".join(f"%({key})s" for key in record.keys())

    @staticmethod
    def conflict_update_columns(columns: Sequence[str], excluded: Sequence[str] = ()) -> str:
        """Build an ON CONFLICT update assignment list.

        Args:
            columns: Column names that may be updated from EXCLUDED values.
            excluded: Column names to skip, commonly primary keys or immutable timestamps.
        """
        excluded_set = set(excluded)
        return ", ".join(
            f"{column} = EXCLUDED.{column}"
            for column in columns
            if column not in excluded_set
        )
