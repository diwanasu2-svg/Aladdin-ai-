"""PostgreSQL connection helper for Aladdin AI scientific memory.

This module intentionally stays small and dependency-light. It uses psycopg 3
when available and exposes a single Database class for services that need to
store or retrieve papers, reactions, experiments, predictions, simulations,
datasets, trained models, and generated reports.
"""

from __future__ import annotations

import os
from contextlib import contextmanager
from typing import Any, Generator, Iterable, Mapping, Optional, Sequence

try:
    import psycopg
    from psycopg.rows import dict_row
except ImportError as exc:  # pragma: no cover - exercised only without dependency
    psycopg = None
    dict_row = None
    _IMPORT_ERROR = exc
else:
    _IMPORT_ERROR = None

Parameters = Optional[Sequence[Any] | Mapping[str, Any]]


class DatabaseError(RuntimeError):
    """Raised when the PostgreSQL driver or connection is unavailable."""


class Database:
    """Manage PostgreSQL access for Aladdin AI services.

    Example:
        db = Database.from_env()
        db.connect()
        reaction = db.fetch_one(
            "SELECT * FROM reactions WHERE reaction_id = %s",
            ("RXN_001",),
        )
        db.disconnect()
    """

    def __init__(self, dsn: str, *, autocommit: bool = True) -> None:
        self.dsn = dsn
        self.autocommit = autocommit
        self._connection: Any | None = None

    @classmethod
    def from_env(cls, env_var: str = "DATABASE_URL", *, autocommit: bool = True) -> "Database":
        """Build a Database instance from an environment variable."""
        dsn = os.getenv(env_var)
        if not dsn:
            raise DatabaseError(f"{env_var} is not set")
        return cls(dsn, autocommit=autocommit)

    @property
    def is_connected(self) -> bool:
        """Return True when an open psycopg connection is available."""
        return self._connection is not None and not self._connection.closed

    def connect(self) -> None:
        """Open the PostgreSQL connection if it is not already open."""
        if self.is_connected:
            return
        if psycopg is None:
            raise DatabaseError("psycopg is required for PostgreSQL access") from _IMPORT_ERROR
        self._connection = psycopg.connect(self.dsn, autocommit=self.autocommit, row_factory=dict_row)

    def disconnect(self) -> None:
        """Close the active PostgreSQL connection."""
        if self._connection is not None:
            self._connection.close()
            self._connection = None

    def execute(self, query: str, params: Parameters = None) -> int:
        """Execute a write/query statement and return affected row count."""
        connection = self._require_connection()
        with connection.cursor() as cursor:
            cursor.execute(query, params)
            return cursor.rowcount

    def fetch_one(self, query: str, params: Parameters = None) -> dict[str, Any] | None:
        """Execute a query and return one row as a dict, or None."""
        connection = self._require_connection()
        with connection.cursor() as cursor:
            cursor.execute(query, params)
            return cursor.fetchone()

    def fetch_all(self, query: str, params: Parameters = None) -> list[dict[str, Any]]:
        """Execute a query and return all rows as dictionaries."""
        connection = self._require_connection()
        with connection.cursor() as cursor:
            cursor.execute(query, params)
            return list(cursor.fetchall())

    def execute_many(self, query: str, params_seq: Iterable[Parameters]) -> int:
        """Execute one statement for many parameter sets and return row count."""
        connection = self._require_connection()
        with connection.cursor() as cursor:
            cursor.executemany(query, params_seq)
            return cursor.rowcount

    @contextmanager
    def transaction(self) -> Generator[Any, None, None]:
        """Run a group of operations inside a database transaction."""
        connection = self._require_connection()
        with connection.transaction() as tx:
            yield tx

    def __enter__(self) -> "Database":
        self.connect()
        return self

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> None:
        self.disconnect()

    def _require_connection(self) -> Any:
        if not self.is_connected:
            self.connect()
        if self._connection is None:
            raise DatabaseError("Database connection could not be established")
        return self._connection
