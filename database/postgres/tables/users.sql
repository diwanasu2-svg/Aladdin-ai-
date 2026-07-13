-- =====================================================================
-- users table
-- Purpose: Tracks who/what is operating within the research system --
--          human researchers as well as AI agents.
-- =====================================================================

CREATE TABLE IF NOT EXISTS users (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(100) NOT NULL UNIQUE,
    email           VARCHAR(255) NOT NULL UNIQUE,
    role            VARCHAR(20)  NOT NULL DEFAULT 'guest'
                         CHECK (role IN ('admin', 'scientist', 'reviewer', 'ai_agent', 'guest')),
    status          VARCHAR(20)  NOT NULL DEFAULT 'active'
                         CHECK (status IN ('active', 'suspended', 'pending_approval', 'disabled')),
    last_login      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_users_role   ON users (role);
CREATE INDEX IF NOT EXISTS idx_users_status ON users (status);

-- Future use: approval workflows (pending_approval -> active) will be
-- driven off of the `status` column here.
