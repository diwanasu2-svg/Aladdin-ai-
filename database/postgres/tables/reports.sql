-- =====================================================================
-- reports table
-- Purpose: Generated research reports.
-- Future use: automatic paper writing.
-- =====================================================================

CREATE TABLE IF NOT EXISTS reports (
    report_id       VARCHAR(32)  PRIMARY KEY,             -- e.g. REPORT_0001
    title           TEXT NOT NULL,
    project         BIGINT REFERENCES projects(id) ON DELETE SET NULL,
    author          BIGINT REFERENCES users(id) ON DELETE SET NULL,
    pdf_path        TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_reports_project ON reports (project);
CREATE INDEX IF NOT EXISTS idx_reports_author  ON reports (author);
