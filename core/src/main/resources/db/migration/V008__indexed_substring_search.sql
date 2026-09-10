-- Indexed substring search for symbols and relations.
--
-- The embedded Xerial SQLite baseline is qualified by
-- SqliteIndexedSubstringSearchTest, which probes FTS5 and the trigram tokenizer
-- before asserting query-plan usage. Queries with at least three Unicode code
-- points use these derived indexes; shorter compatibility queries retain the
-- legacy LIKE fallback in SqliteIndexRepository.
--
-- FTS5 is a derived, contentless projection. detail=none removes positional
-- information and columnsize=0 avoids the per-row docsize shadow table. The
-- canonical values remain in symbols / symbol_relations. INSERT mutations stage
-- only entity ids; indexed text is read once from canonical rows when a batch is
-- flushed. UPDATE/DELETE mutations retain only the first pre-change text needed
-- by the FTS5 delete command. This avoids duplicating every inserted search text
-- in ordinary pending tables while preserving transactional generation flushes.
--
-- The migration is replay-safe for the legacy checksum-table recovery test.
-- Derived structures are rebuilt deterministically when V008 is replayed.

CREATE INDEX IF NOT EXISTS idx_symbols_fuzzy_prefilter
    ON symbols(SUBSTR(LOWER(name), 1, 1), LENGTH(name));

DROP TRIGGER IF EXISTS symbols_search_pending_ai;
DROP TRIGGER IF EXISTS symbols_search_pending_au;
DROP TRIGGER IF EXISTS symbols_search_pending_ad;
DROP TRIGGER IF EXISTS relations_search_pending_ai;
DROP TRIGGER IF EXISTS relations_search_pending_au;
DROP TRIGGER IF EXISTS relations_search_pending_ad;
DROP TRIGGER IF EXISTS search_projection_flush_generation_ai;
DROP TRIGGER IF EXISTS search_projection_flush_generation_au;
DROP TRIGGER IF EXISTS search_projection_flush_signal_au;
DROP TRIGGER IF EXISTS project_index_generation_row_ai;

DROP TABLE IF EXISTS symbol_search_pending;
DROP TABLE IF EXISTS relation_search_pending;
DROP TABLE IF EXISTS search_projection_flush_signal;
DROP TABLE IF EXISTS symbol_search_fts;
DROP TABLE IF EXISTS relation_search_fts;

CREATE VIRTUAL TABLE symbol_search_fts USING fts5(
    search_text,
    tokenize='trigram',
    content='',
    detail='none',
    columnsize=0
);
INSERT INTO symbol_search_fts(rowid, search_text)
SELECT s.id, s.name || char(10) || s.qualified_name
FROM symbols s;

CREATE TABLE symbol_search_pending (
    entity_id INTEGER PRIMARY KEY,
    delete_text TEXT
);

CREATE TRIGGER symbols_search_pending_ai
AFTER INSERT ON symbols
BEGIN
    INSERT OR IGNORE INTO symbol_search_pending(entity_id, delete_text)
    VALUES (NEW.id, NULL);

    INSERT INTO symbol_search_fts(symbol_search_fts, rowid, search_text)
    SELECT 'delete', entity_id, delete_text
    FROM symbol_search_pending
    WHERE delete_text IS NOT NULL
      AND entity_id IN (
          SELECT entity_id
          FROM symbol_search_pending
          ORDER BY entity_id
          LIMIT 5000
      )
      AND (NEW.id % 5000) = 0;

    INSERT INTO symbol_search_fts(rowid, search_text)
    SELECT s.id, s.name || char(10) || s.qualified_name
    FROM symbols s
    JOIN (
        SELECT entity_id
        FROM symbol_search_pending
        ORDER BY entity_id
        LIMIT 5000
    ) pending ON pending.entity_id = s.id
    WHERE (NEW.id % 5000) = 0
    ORDER BY s.id;

    DELETE FROM symbol_search_pending
    WHERE entity_id IN (
        SELECT entity_id
        FROM symbol_search_pending
        ORDER BY entity_id
        LIMIT 5000
    )
      AND (NEW.id % 5000) = 0;
END;

CREATE TRIGGER symbols_search_pending_au
AFTER UPDATE OF file_id, name, qualified_name ON symbols
BEGIN
    INSERT INTO symbol_search_pending(entity_id, delete_text)
    VALUES (NEW.id, OLD.name || char(10) || OLD.qualified_name)
    ON CONFLICT(entity_id) DO NOTHING;
END;

CREATE TRIGGER symbols_search_pending_ad
AFTER DELETE ON symbols
BEGIN
    INSERT INTO symbol_search_pending(entity_id, delete_text)
    VALUES (OLD.id, OLD.name || char(10) || OLD.qualified_name)
    ON CONFLICT(entity_id) DO NOTHING;
END;

CREATE VIRTUAL TABLE relation_search_fts USING fts5(
    search_text,
    tokenize='trigram',
    content='',
    detail='none',
    columnsize=0
);
INSERT INTO relation_search_fts(rowid, search_text)
SELECT id, source_ref || char(10) || target_ref
FROM symbol_relations;

CREATE TABLE relation_search_pending (
    entity_id INTEGER PRIMARY KEY,
    delete_text TEXT
);

CREATE TRIGGER relations_search_pending_ai
AFTER INSERT ON symbol_relations
BEGIN
    INSERT OR IGNORE INTO relation_search_pending(entity_id, delete_text)
    VALUES (NEW.id, NULL);

    INSERT INTO relation_search_fts(relation_search_fts, rowid, search_text)
    SELECT 'delete', entity_id, delete_text
    FROM relation_search_pending
    WHERE delete_text IS NOT NULL
      AND entity_id IN (
          SELECT entity_id
          FROM relation_search_pending
          ORDER BY entity_id
          LIMIT 5000
      )
      AND (NEW.id % 5000) = 0;

    INSERT INTO relation_search_fts(rowid, search_text)
    SELECT r.id, r.source_ref || char(10) || r.target_ref
    FROM symbol_relations r
    JOIN (
        SELECT entity_id
        FROM relation_search_pending
        ORDER BY entity_id
        LIMIT 5000
    ) pending ON pending.entity_id = r.id
    WHERE (NEW.id % 5000) = 0
    ORDER BY r.id;

    DELETE FROM relation_search_pending
    WHERE entity_id IN (
        SELECT entity_id
        FROM relation_search_pending
        ORDER BY entity_id
        LIMIT 5000
    )
      AND (NEW.id % 5000) = 0;
END;

CREATE TRIGGER relations_search_pending_au
AFTER UPDATE OF project_id, source_ref, target_ref ON symbol_relations
BEGIN
    INSERT INTO relation_search_pending(entity_id, delete_text)
    VALUES (NEW.id, OLD.source_ref || char(10) || OLD.target_ref)
    ON CONFLICT(entity_id) DO NOTHING;
END;

CREATE TRIGGER relations_search_pending_ad
AFTER DELETE ON symbol_relations
BEGIN
    INSERT INTO relation_search_pending(entity_id, delete_text)
    VALUES (OLD.id, OLD.source_ref || char(10) || OLD.target_ref)
    ON CONFLICT(entity_id) DO NOTHING;
END;

-- Keep generation-row creation lazy, as it was before V008, so callers may
-- explicitly seed the initial generation without a primary-key conflict.
DROP TRIGGER IF EXISTS project_index_generation_row_ai;

-- Both first-generation INSERTs and subsequent generation UPDATEs advance one
-- derived signal row. The signal routes both events through a single projection
-- flush body without changing canonical generation values.
CREATE TABLE search_projection_flush_signal (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    sequence INTEGER NOT NULL
);
INSERT INTO search_projection_flush_signal(id, sequence) VALUES (1, 0);

CREATE TRIGGER search_projection_flush_generation_ai
AFTER INSERT ON project_index_generations
BEGIN
    UPDATE search_projection_flush_signal
    SET sequence = sequence + 1
    WHERE id = 1;
END;

CREATE TRIGGER search_projection_flush_generation_au
AFTER UPDATE OF generation ON project_index_generations
BEGIN
    UPDATE search_projection_flush_signal
    SET sequence = sequence + 1
    WHERE id = 1;
END;

CREATE TRIGGER search_projection_flush_signal_au
AFTER UPDATE OF sequence ON search_projection_flush_signal
BEGIN
    INSERT INTO symbol_search_fts(symbol_search_fts, rowid, search_text)
    SELECT 'delete', entity_id, delete_text
    FROM symbol_search_pending
    WHERE delete_text IS NOT NULL;
    INSERT INTO symbol_search_fts(rowid, search_text)
    SELECT s.id, s.name || char(10) || s.qualified_name
    FROM symbols s
    JOIN symbol_search_pending pending ON pending.entity_id = s.id
    ORDER BY s.id;
    DELETE FROM symbol_search_pending;

    INSERT INTO relation_search_fts(relation_search_fts, rowid, search_text)
    SELECT 'delete', entity_id, delete_text
    FROM relation_search_pending
    WHERE delete_text IS NOT NULL;
    INSERT INTO relation_search_fts(rowid, search_text)
    SELECT r.id, r.source_ref || char(10) || r.target_ref
    FROM symbol_relations r
    JOIN relation_search_pending pending ON pending.entity_id = r.id
    ORDER BY r.id;
    DELETE FROM relation_search_pending;
END;