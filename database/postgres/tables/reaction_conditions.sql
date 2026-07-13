-- =====================================================================
-- reaction_conditions table
-- Purpose: Keeps the reactions table clean by breaking out detailed
--          process conditions separately (1 reaction -> many condition
--          sets / runs).
--          Example: Nitrogen Atmosphere | 80C | 3 hours
-- Future use: the optimization engine tunes exactly these parameters.
-- =====================================================================

CREATE TABLE IF NOT EXISTS reaction_conditions (
    id              BIGSERIAL PRIMARY KEY,
    reaction_id     VARCHAR(32) NOT NULL REFERENCES reactions(reaction_id) ON DELETE CASCADE,
    temperature     NUMERIC(10, 2),
    pressure        NUMERIC(10, 2),
    ph              NUMERIC(5, 2),
    stir_rate       NUMERIC(10, 2),                        -- rpm
    atmosphere      VARCHAR(100),                          -- e.g. Nitrogen, Argon, Air
    reaction_time   NUMERIC(10, 2),                         -- hours
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_reaction_conditions_reaction ON reaction_conditions (reaction_id);
