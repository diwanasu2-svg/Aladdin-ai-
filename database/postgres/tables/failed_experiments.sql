-- =====================================================================
-- failed_experiments table
-- Purpose: Critically important -- most systems fail because they do
--          not persist this. Captures WHY an experiment failed so the
--          AI does not repeat the same mistake.
--          Example: "Temperature too high", "Catalyst deactivated",
--                   "Impurity formation"
-- =====================================================================

CREATE TABLE IF NOT EXISTS failed_experiments (
    id              BIGSERIAL PRIMARY KEY,
    experiment_id   VARCHAR(32) NOT NULL REFERENCES experiments(experiment_id) ON DELETE CASCADE,
    failure_reason  TEXT NOT NULL,
    conditions      JSONB,                                 -- snapshot of conditions at failure
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_failed_experiments_experiment ON failed_experiments (experiment_id);
