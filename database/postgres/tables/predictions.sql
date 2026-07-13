-- =====================================================================
-- predictions table
-- Purpose: Every prediction the AI has ever made.
--          Example: Predicted Yield: 72% | Confidence: 0.81
-- Future use: model evaluation, calibration, retraining.
-- =====================================================================

CREATE TABLE IF NOT EXISTS predictions (
    prediction_id   BIGSERIAL PRIMARY KEY,
    input_data      JSONB NOT NULL,
    output_data     JSONB NOT NULL,
    confidence      NUMERIC(5, 4),                         -- 0.0 - 1.0
    uncertainty     NUMERIC(10, 6),
    model_version   VARCHAR(100),
    timestamp       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_predictions_model_version ON predictions (model_version);
CREATE INDEX IF NOT EXISTS idx_predictions_timestamp      ON predictions (timestamp);
