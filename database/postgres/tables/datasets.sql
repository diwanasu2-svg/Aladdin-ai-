-- =====================================================================
-- datasets table
-- Purpose: Every training dataset used across the AI's models.
--          Example: ORD | Version 2
-- Future use: model lineage tracking.
-- =====================================================================

CREATE TABLE IF NOT EXISTS datasets (
    dataset_id      VARCHAR(32)  PRIMARY KEY,             -- e.g. DS_0001
    name            VARCHAR(255) NOT NULL,
    source          VARCHAR(255),
    version         VARCHAR(50),
    size_bytes      BIGINT,
    license         VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_datasets_name ON datasets (name);
