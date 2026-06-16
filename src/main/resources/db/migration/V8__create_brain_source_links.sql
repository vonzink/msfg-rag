-- Editable registry of external source/links the brain is allowed to cite or
-- surface (the trust layer, spec §6.1). Full-CRUD collection: create / list /
-- get / update / delete (hard) / activate / deactivate; one row per link.
-- Seeded from the optional pack file source-links.yaml on first boot
-- (idempotent — only when the table is empty). An empty table plus no pack file
-- reproduces today's behavior exactly: nothing in this table is read by the ask
-- pipeline in Phase 3.
CREATE TABLE brain_source_links (
    id                 UUID         PRIMARY KEY,
    name               VARCHAR(500) NOT NULL,
    url                VARCHAR(2000) NOT NULL,
    domain             VARCHAR(255),
    authority          VARCHAR(50)  NOT NULL,                  -- PRIMARY | SECONDARY | BACKGROUND
    topics             JSONB        NOT NULL DEFAULT '[]'::jsonb,   -- string[]
    freshness_required BOOLEAN      NOT NULL DEFAULT FALSE,
    allowed_use        JSONB        NOT NULL DEFAULT '[]'::jsonb,   -- string[]
    do_not_use_for     JSONB        NOT NULL DEFAULT '[]'::jsonb,   -- string[]
    surface            VARCHAR(50)  NOT NULL,                  -- PUBLIC | INTERNAL | BOTH
    is_active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ  NOT NULL,
    created_by         VARCHAR(100) NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    updated_by         VARCHAR(100) NOT NULL
);
CREATE INDEX idx_source_links_active ON brain_source_links (is_active);
CREATE INDEX idx_source_links_created ON brain_source_links (created_at DESC, id DESC);
