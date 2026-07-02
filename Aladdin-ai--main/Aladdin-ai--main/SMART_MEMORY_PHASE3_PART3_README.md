# Phase 3 — Smart Memory (Part 3, Part 1)

This update adds the semantic retrieval layer on top of the existing Smart
Memory Part 1 and Part 2 implementation without removing any prior behavior.

## Added modules

| Module | File | Purpose |
|---|---|---|
| Importance Scoring | `aladdin_core/memory_importance.py` | Scores memory importance using category, source, explicit priority and structural signals |
| Memory Ranking | `aladdin_core/memory_ranking.py` | Ranks matches using similarity + importance + recency |
| Semantic Search | `aladdin_core/semantic_search.py` | Search API for vector-based retrieval |
| Embedding Manager | `aladdin_core/embedding_manager.py` | Dependency-free hashed embeddings for offline semantic matching |
| Vector Database | `aladdin_core/vector_store.py` | SQLite-backed persistent vector index |

## Integration points

`MemoryManager` now initializes and wires all Part 3 components when
`memory.semantic_search_enabled` and `memory.vector_store_enabled` are true.
The manager also automatically indexes:

- profile fields
- preferences
- facts
- contacts
- projects
- locations
- reminders
- calendar events
- conversation summaries

## New `MemoryManager` APIs

```python
mem.score_memory_importance(
    "Reminder to renew passport next week",
    category="reminder",
    source_type="reminder",
    metadata={"due_at": "2026-06-30", "priority": 4},
)

mem.semantic_search_memories("passport renewal", limit=5)
mem.rank_memory_results(existing_results, query="passport")
mem.rebuild_semantic_index()
mem.get_semantic_index_stats()
```

## Configuration

Add under `memory:` in `config.yaml`:

```yaml
memory:
  importance_scoring_enabled: true
  vector_store_enabled: true
  vector_store_db_path: data/memory_vectors.sqlite
  embedding_dimensions: 256
  embedding_cache_size: 1024
  semantic_search_default_limit: 5
  semantic_search_min_similarity: 0.12
  semantic_search_rebuild_on_start: true
  ranking_similarity_bias: 0.55
  ranking_importance_bias: 0.30
  ranking_recency_bias: 0.15
```

## Storage

The vector database is persisted in SQLite at:

- `data/memory_vectors.sqlite`

Each row stores:
- stable record id
- source type / source id
- human-readable text
- metadata JSON
- normalized embedding vector
- computed importance score
- timestamps

## Backward compatibility

- Part 1 and Part 2 APIs are preserved
- Existing methods keep the same names and return shapes
- Part 3 is additive and config-gated
- No new mandatory third-party dependency was introduced
- Legacy `ConversationMemory` remains available via `aladdin_core.memory`

## Validation goals for this update

- clean imports for all new memory modules
- syntax compilation for updated files
- `MemoryManager` creation with Part 3 enabled and disabled
- semantic indexing + semantic search smoke test
- Part 1 + Part 2 memory CRUD smoke tests still passing
