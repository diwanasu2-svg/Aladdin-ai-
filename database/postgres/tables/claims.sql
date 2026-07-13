-- =====================================================================
-- claims table
-- Purpose: Scientific claims extracted from within a paper.
--          Example: "Catalyst A increased yield by 20%"
-- Future use: contradiction detector, novelty detector, research planner.
-- =====================================================================

CREATE TABLE IF NOT EXISTS claims (
    claim_id        BIGSERIAL PRIMARY KEY,
    paper_id        VARCHAR(32) NOT NULL REFERENCES papers(paper_id) ON DELETE CASCADE,
    claim_text      TEXT NOT NULL,
    confidence      NUMERIC(5, 4),                         -- 0.0 - 1.0
    evidence        TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_claims_paper ON claims (paper_id);
