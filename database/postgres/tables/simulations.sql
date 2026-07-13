-- =====================================================================
-- simulations table
-- Purpose: Every simulation the research AI has run.
--          Engines: OpenMM, Psi4, CFD, MonteCarlo
-- =====================================================================

CREATE TABLE IF NOT EXISTS simulations (
    simulation_id   VARCHAR(32)  PRIMARY KEY,             -- e.g. SIM_0001
    engine          VARCHAR(50) NOT NULL
                         CHECK (engine IN ('OpenMM', 'Psi4', 'CFD', 'MonteCarlo', 'Other')),
    input           JSONB NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'queued'
                         CHECK (status IN ('queued', 'running', 'completed', 'failed')),
    runtime_seconds NUMERIC(12, 3),
    output          JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_simulations_engine ON simulations (engine);
CREATE INDEX IF NOT EXISTS idx_simulations_status ON simulations (status);
