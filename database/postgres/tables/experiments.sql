-- =====================================================================
-- experiments table
-- Purpose: Real, physical experiments run by researchers.
--          Status lifecycle: planned -> running -> completed | failed
-- =====================================================================

CREATE TABLE IF NOT EXISTS experiments (
    experiment_id   VARCHAR(32)  PRIMARY KEY,             -- e.g. EXP_0001
    project_id      BIGINT REFERENCES projects(id) ON DELETE SET NULL,
    objective       TEXT,
    protocol        TEXT,
    researcher      BIGINT REFERENCES users(id) ON DELETE SET NULL,
    date            DATE,
    status          VARCHAR(20) NOT NULL DEFAULT 'planned'
                         CHECK (status IN ('planned', 'running', 'completed', 'failed')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_experiments_project    ON experiments (project_id);
CREATE INDEX IF NOT EXISTS idx_experiments_researcher ON experiments (researcher);
CREATE INDEX IF NOT EXISTS idx_experiments_status     ON experiments (status);
