-- =====================================================================
-- simulation_results table
-- Purpose: Detailed simulation outputs.
-- Future use: evidence layer for predictions and reactions.
-- =====================================================================

CREATE TABLE IF NOT EXISTS simulation_results (
    id              BIGSERIAL PRIMARY KEY,
    simulation_id   VARCHAR(32) NOT NULL REFERENCES simulations(simulation_id) ON DELETE CASCADE,
    energy          NUMERIC(18, 6),
    stability       NUMERIC(10, 4),
    properties      JSONB,
    files           JSONB,                                 -- array of output file paths
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_simulation_results_simulation ON simulation_results (simulation_id);
