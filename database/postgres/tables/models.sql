-- =====================================================================
-- models table
-- Purpose: Every trained model produced/used by the AI.
--          Example: ReactionPredictor | v1.0
-- Future use: MLflow integration, model lineage.
-- =====================================================================

CREATE TABLE IF NOT EXISTS models (
    model_id        VARCHAR(32)  PRIMARY KEY,             -- e.g. MODEL_0001
    name            VARCHAR(255) NOT NULL,
    version         VARCHAR(50) NOT NULL,
    dataset_id      VARCHAR(32) REFERENCES datasets(dataset_id) ON DELETE SET NULL,
    metrics         JSONB,                                 -- e.g. {"rmse": 0.12, "r2": 0.91}
    path            TEXT,                                  -- storage location of model weights
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (name, version)
);

CREATE INDEX IF NOT EXISTS idx_models_dataset ON models (dataset_id);
