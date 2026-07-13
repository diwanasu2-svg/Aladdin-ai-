-- =====================================================================
-- citations table
-- Purpose: Connections between papers (source cites target).
--          Example: Paper A cites Paper B.
-- Future use: citation graph, knowledge graph.
-- =====================================================================

CREATE TABLE IF NOT EXISTS citations (
    id              BIGSERIAL PRIMARY KEY,
    source_paper    VARCHAR(32) NOT NULL REFERENCES papers(paper_id) ON DELETE CASCADE,
    target_paper    VARCHAR(32) NOT NULL REFERENCES papers(paper_id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (source_paper, target_paper)
);

CREATE INDEX IF NOT EXISTS idx_citations_source ON citations (source_paper);
CREATE INDEX IF NOT EXISTS idx_citations_target ON citations (target_paper);
