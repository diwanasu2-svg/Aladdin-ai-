-- =====================================================================
-- Migration: 001_core_scientific_memory
-- Purpose: Core PostgreSQL scientific memory schema for Aladdin AI.
-- Apply:   psql "$DATABASE_URL" -f database/postgres/migrations/001_core_scientific_memory.sql
-- =====================================================================

BEGIN;

\i database/postgres/tables/users.sql
\i database/postgres/tables/projects.sql
\i database/postgres/tables/compounds.sql
\i database/postgres/tables/descriptors.sql
\i database/postgres/tables/papers.sql
\i database/postgres/tables/claims.sql
\i database/postgres/tables/citations.sql
\i database/postgres/tables/reactions.sql
\i database/postgres/tables/reaction_conditions.sql
\i database/postgres/tables/experiments.sql
\i database/postgres/tables/experiment_results.sql
\i database/postgres/tables/failed_experiments.sql
\i database/postgres/tables/datasets.sql
\i database/postgres/tables/models.sql
\i database/postgres/tables/predictions.sql
\i database/postgres/tables/prediction_feedback.sql
\i database/postgres/tables/simulations.sql
\i database/postgres/tables/simulation_results.sql
\i database/postgres/tables/reports.sql

COMMIT;
