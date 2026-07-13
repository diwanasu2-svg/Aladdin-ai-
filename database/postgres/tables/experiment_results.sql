-- =====================================================================
-- experiment_results table
-- Purpose: Actual measured outputs of an experiment.
-- Future use: prediction vs. reality comparison.
-- =====================================================================

CREATE TABLE IF NOT EXISTS experiment_results (
    id              BIGSERIAL PRIMARY KEY,
    experiment_id   VARCHAR(32) NOT NULL REFERENCES experiments(experiment_id) ON DELETE CASCADE,
    yield_percent   NUMERIC(6, 3),
    purity_percent  NUMERIC(6, 3),
    observations    TEXT,
    notes           TEXT,
    attachments     JSONB,                                 -- array of file paths / URLs
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_experiment_results_experiment ON experiment_results (experiment_id);
