-- Append-only revisions of the editable retrieval vocabulary (borrower/broker
-- synonyms -> guideline vocabulary). The effective text is the newest row;
-- NULL content means "use the pack default" (an explicit, attributable revert).
-- Pack defaults are revision zero, implicit and immutable. There is one logical
-- vocabulary document, so this table needs no key column.
CREATE TABLE brain_vocabulary_revisions (
    id          UUID         PRIMARY KEY,
    content     TEXT,
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(100) NOT NULL
);
CREATE INDEX idx_vocab_revisions_created ON brain_vocabulary_revisions (created_at DESC, id DESC);
