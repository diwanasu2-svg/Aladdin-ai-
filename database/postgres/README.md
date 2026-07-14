# Aladdin AI PostgreSQL Scientific Memory Layer

This folder is the Part 1A PostgreSQL core database layer for Aladdin AI. It is
the durable scientific memory used by future prediction, simulation, knowledge
graph, research planning, and continuous-learning modules.

## Folder responsibilities

```text
database/postgres/
├── init.sql                         # One-shot schema bootstrap for local/dev DBs
├── database.py                      # Python connection/query/transaction helper
├── tables/                          # Table-level DDL files, split by domain
├── migrations/                      # Versioned schema entrypoints
└── repositories/                    # Python data-access classes for services
```

## Scientific memory domains

The schema stores the main evidence types that downstream AI modules need:

- **Users and projects**: researchers, AI agents, approval workflows, and research initiatives.
- **Compounds and descriptors**: chemical identities plus calculated features for RDKit/property models.
- **Papers, claims, and citations**: literature ingestion, evidence extraction, and citation graph memory.
- **Reactions and conditions**: reaction knowledge used to train and run reaction prediction services.
- **Experiments and failures**: real lab protocols, measured outputs, and failed-experiment memory.
- **Predictions and feedback**: AI outputs, uncertainty, verification, error tracking, and retraining signals.
- **Simulations and results**: computational runs from engines such as OpenMM, Psi4, CFD, and MonteCarlo.
- **Datasets and models**: training data lineage, model versions, metrics, and artifact paths.
- **Reports**: generated scientific reports and future automatic paper-writing outputs.

## Applying the schema

For a fresh database, run:

```bash
psql "$DATABASE_URL" -f database/postgres/init.sql
```

For migration-based setup, start from:

```bash
psql "$DATABASE_URL" -f database/postgres/migrations/001_core_scientific_memory.sql
```

Both scripts load the table DDL files in dependency-safe order so foreign keys
are created after their parent tables exist.

## Python usage

```python
from database.postgres.database import Database

with Database.from_env() as db:
    reaction = db.fetch_one(
        "SELECT * FROM reactions WHERE reaction_id = %s",
        ("RXN_001",),
    )
```

Use `Database.transaction()` when multiple writes must succeed or fail together.
Repository classes in `database/postgres/repositories/` should receive a
`Database` instance rather than opening their own connections.
