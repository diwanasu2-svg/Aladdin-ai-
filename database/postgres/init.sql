-- =====================================================================
-- Aladdin AI PostgreSQL scientific memory bootstrap
-- Purpose: Load the core schema used by prediction, simulation,
--          knowledge graph, memory, and research-planning modules.
-- Usage:   psql "$DATABASE_URL" -f database/postgres/init.sql
-- =====================================================================

BEGIN;

-- Load order matters because several tables reference users, projects,
-- papers, datasets, models, experiments, predictions, simulations, and
-- reactions through foreign keys.
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
