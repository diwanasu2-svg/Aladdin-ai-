-- =====================================================================
-- compounds table
-- Purpose: The single most important table -- every chemical the
--          research AI has ever encountered lives here.
--          Example: CMP_0001 | Water | H2O | O | InChI=1S/H2O | 18.015
-- Future use: RDKit will read/write directly against this table for
--          canonicalization, descriptor generation, and similarity search.
-- =====================================================================

CREATE TABLE IF NOT EXISTS compounds (
    compound_id       VARCHAR(32)  PRIMARY KEY,          -- e.g. CMP_0001
    name              VARCHAR(255) NOT NULL,
    formula           VARCHAR(100),
    smiles            TEXT,
    inchi             TEXT,
    molecular_weight  NUMERIC(12, 4),
    density           NUMERIC(12, 4),
    phase             VARCHAR(50),                        -- e.g. organic, inorganic
    state             VARCHAR(20)
                          CHECK (state IN ('solid', 'liquid', 'gas', 'plasma', 'unknown')),
    color             VARCHAR(50),
    hazards           JSONB,                               -- flexible hazard/GHS metadata
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_compounds_name    ON compounds (name);
CREATE INDEX IF NOT EXISTS idx_compounds_formula ON compounds (formula);
CREATE INDEX IF NOT EXISTS idx_compounds_smiles  ON compounds USING HASH (smiles);
