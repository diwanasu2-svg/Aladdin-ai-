-- =====================================================================
-- prediction_feedback table
-- Purpose: Records whether a prediction turned out right or wrong.
-- Future use: continuous learning loop.
-- =====================================================================

CREATE TABLE IF NOT EXISTS prediction_feedback (
    id              BIGSERIAL PRIMARY KEY,
    prediction_id   BIGINT NOT NULL REFERENCES predictions(prediction_id) ON DELETE CASCADE,
    actual_result   JSONB,
    error           NUMERIC(12, 6),
    verified        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_prediction_feedback_prediction ON prediction_feedback (prediction_id);
