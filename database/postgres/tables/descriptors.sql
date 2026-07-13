-- =====================================================================
-- descriptors table
-- Purpose: Calculated molecular features per compound.
-- Future use: property prediction models, reaction prediction models,
--          and similarity search all consume this table.
-- =====================================================================

CREATE TABLE IF NOT EXISTS descriptors (
    id                BIGSERIAL PRIMARY KEY,
    compound_id       VARCHAR(32) NOT NULL REFERENCES compounds(compound_id) ON DELETE CASCADE,
    log_p             NUMERIC(10, 4),
    tpsa              NUMERIC(10, 4),
    hba               INTEGER,
    hbd               INTEGER,
    rotatable_bonds   INTEGER,
    ring_count        INTEGER,
    fingerprint       BYTEA,                               -- binary/bit-vector fingerprint
    computed_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (compound_id)
);

CREATE INDEX IF NOT EXISTS idx_descriptors_compound ON descriptors (compound_id);
