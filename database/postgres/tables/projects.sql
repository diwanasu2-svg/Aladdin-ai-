-- =====================================================================
-- projects table
-- Purpose: Top level research initiatives, e.g. "Battery Research",
--          "Catalyst Discovery", "Polymer Optimization", "Drug Design".
-- =====================================================================

CREATE TABLE IF NOT EXISTS projects (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    owner           BIGINT REFERENCES users(id) ON DELETE SET NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'active'
                         CHECK (status IN ('active', 'paused', 'completed', 'archived')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_projects_owner  ON projects (owner);
CREATE INDEX IF NOT EXISTS idx_projects_status ON projects (status);
