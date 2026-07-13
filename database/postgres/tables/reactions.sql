-- =====================================================================
-- reactions table
-- Purpose: The heart of the whole AI -- every chemical reaction the
--          system knows about.
--          Example: RXN_001 | Acetylation | Acetic Acid + Ethanol ->
--                   Ethyl Acetate | catalyst: Sulfuric Acid | 78C | 5h
-- Future use: the reaction prediction service trains directly on this table.
-- =====================================================================

CREATE TABLE IF NOT EXISTS reactions (
    reaction_id     VARCHAR(32)  PRIMARY KEY,             -- e.g. RXN_001
    name            VARCHAR(255) NOT NULL,
    reactants       JSONB NOT NULL,                        -- array of compound_id / qty
    products        JSONB NOT NULL,                        -- array of compound_id / qty
    catalyst        VARCHAR(255),
    solvent         VARCHAR(255),
    temperature     NUMERIC(10, 2),                        -- degrees C
    pressure        NUMERIC(10, 2),                        -- atm
    time_hours      NUMERIC(10, 2),
    yield_percent   NUMERIC(6, 3),
    paper_id        VARCHAR(32) REFERENCES papers(paper_id) ON DELETE SET NULL,
    confidence      NUMERIC(5, 4),                         -- 0.0 - 1.0
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_reactions_paper ON reactions (paper_id);
CREATE INDEX IF NOT EXISTS idx_reactions_name  ON reactions (name);
