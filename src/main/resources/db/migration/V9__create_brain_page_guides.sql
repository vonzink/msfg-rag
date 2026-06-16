-- Editable registry of page guides — "where the user should go" (spec §6.1).
-- Route-keyed (route may be null for topic-matched-only guides) and topic-
-- matchable. Full-CRUD collection: create / list / get / update / delete (hard) /
-- activate / deactivate; one row per guide. Seeded from the optional pack file
-- page-guides.yaml on first boot (idempotent — only when the table is empty). An
-- empty table plus no pack file reproduces today's behavior exactly: nothing in
-- this table is read by the ask pipeline in Phase 4. The optional `embedding`
-- column from spec §6.1 is intentionally omitted (semantic page-matching is a
-- later phase). source_link_ids holds plain UUIDs referencing brain_source_links
-- by value — NOT a relational FK (spec §6.1 D5).
CREATE TABLE brain_page_guides (
    id                UUID         PRIMARY KEY,
    route             VARCHAR(500),                            -- null → topic-matched only
    title             VARCHAR(500) NOT NULL,
    purpose           TEXT         NOT NULL,
    surface           VARCHAR(50)  NOT NULL,                   -- PUBLIC | INTERNAL | BOTH
    user_intents      JSONB        NOT NULL DEFAULT '[]'::jsonb,   -- string[]
    allowed_guidance  JSONB        NOT NULL DEFAULT '[]'::jsonb,   -- string[]
    internal_links    JSONB        NOT NULL DEFAULT '[]'::jsonb,   -- [{label,url}]
    source_link_ids   JSONB        NOT NULL DEFAULT '[]'::jsonb,   -- UUID[] (by value, not FK)
    topics            JSONB        NOT NULL DEFAULT '[]'::jsonb,   -- string[]
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(100) NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(100) NOT NULL
);
CREATE INDEX idx_page_guides_active ON brain_page_guides (is_active);
CREATE INDEX idx_page_guides_created ON brain_page_guides (created_at DESC, id DESC);
