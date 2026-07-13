-- =====================================================================
-- papers table
-- Purpose: Scientific literature ingested by the research AI.
-- Future use: literature retrieval engine, citation engine, knowledge graph.
-- =====================================================================

CREATE TABLE IF NOT EXISTS papers (
    paper_id        VARCHAR(32)  PRIMARY KEY,             -- e.g. PAPER_0001
    title           TEXT NOT NULL,
    authors         JSONB,                                 -- array of author names
    journal         VARCHAR(255),
    year            INTEGER,
    doi             VARCHAR(255) UNIQUE,
    abstract        TEXT,
    pdf_path        TEXT,
    status          VARCHAR(20)  NOT NULL DEFAULT 'unread'
                         CHECK (status IN ('unread', 'reading', 'read', 'archived')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_papers_year   ON papers (year);
CREATE INDEX IF NOT EXISTS idx_papers_status ON papers (status);
CREATE INDEX IF NOT EXISTS idx_papers_doi    ON papers (doi);
