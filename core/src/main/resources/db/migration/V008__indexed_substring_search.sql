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
-- canonical values remain in symbols / symbol_relations. Canonical mutations
-- are staged in small ordinary tables. Batches of 5 000 are flushed with
-- INSERT ... SELECT; the project generation bump flushes the residual batch in
-- the same transaction.
--
-- Because a contentless FTS table cannot infer the old tokens on DELETE, each
-- pending row keeps the original indexed text when a delete is required and
-- the latest replacement text when an insert is required. The special FTS5
-- 'delete' command is therefore batchable and deterministic.
--
-- The migration is replay-safe for the legacy checksum-table recovery test.
-- Derived structures are rebuilt deterministically when V008 is replayed.

CREATE INDEX IF NOT EXISTS idx_symbols_fuzzy_prefilter
    ON symbols(SUBSTR(LOWER(name), 1, 1), LENGTH(name));

CREATE VIRTUAL TABLE IF NOT EXISTS symbol_search_fts USING fts5(
    search_text,
    tokenize='trigram',
    content='',
    detail='none',
    columnsize=0
);
INSERT INTO symbol_search_fts(symbol_search_fts) VALUES ('delete-all');
INSERT INTO symbol_search_fts(rowid, search_text)
SELECT s.id, s.name || char(10) || s.qualified_name
FROM symbols s;

CREATE TABLE IF NOT EXISTS symbol_search_pending (
    entity_id INTEGER PRIMARY KEY,
    delete_text TEXT,
    insert_text TEXT,
    needs_delete INTEGER NOT NULL CHECK (needs_delete IN (0, 1))
);
DELETE FROM symbol_search_pending WHERE entity_id IS NOT NULL;

DROP TRIGGER IF EXISTS symbols_search_pending_ai;
CREATE TRIGGER symbols_search_pending_ai
AFTER INSERT ON symbols
BEGIN
    INSERT INTO symbol_search_pending(entity_id, delete_text, insert_text, needs_delete)
    VALUES (NEW.id, NULL, NEW.name || char(10) || NEW.qualified_name, 0)
    ON CONFLICT(entity_id) DO UPDATE SET
        insert_text = excluded.insert_text;

    INSERT INTO symbol_search_fts(symbol_search_fts, rowid, search_text)
    SELECT 'delete', entity_id, delete_text
    FROM symbol_search_pending
    WHERE needs_delete = 1
      AND entity_id IN (
          SELECT entity_id FROM symbol_search_pending ORDER BY entity_id LIMIT 5000
      )
      AND (SELECT COUNT(*) FROM symbol_search_pending) >= 5000;

    INSERT INTO symbol_search_fts(rowid, search_text)
    SELECT entity_id, insert_text
    FROM symbol_search_pending
    WHERE insert_text IS NOT NULL
      AND entity_id IN (
          SELECT entity_id FROM symbol_search_pending ORDER BY entity_id LIMIT 5000
      )
      AND (SELECT COUNT(*) FROM symbol_search_pending) >= 5000
    ORDER BY entity_id;

    DELETE FROM symbol_search_pending
    WHERE entity_id IN (
        SELECT entity_id FROM symbol_search_pending ORDER BY entity_id LIMIT 5000
    ) AND (SELECT COUNT(*) FROM symbol_search_pending) >= 5000;
END;

DROP TRIGGER IF EXISTS symbols_search_pending_au;
CREATE TRIGGER symbols_search_pending_au
AFTER UPDATE OF file_id, name, qualified_name ON symbols
BEGIN
    INSERT INTO symbol_search_pending(entity_id, delete_text, insert_text, needs_delete)
    VALUES (
        NEW.id,
        OLD.name || char(10) || OLD.qualified_name,
        NEW.name || char(10) || NEW.qualified_name,
        1
    )
    ON CONFLICT(entity_id) DO UPDATE SET
        insert_text = excluded.insert_text;
END;

DROP TRIGGER IF EXISTS symbols_search_pending_ad;
CREATE TRIGGER symbols_search_pending_ad
AFTER DELETE ON symbols
BEGIN
    INSERT INTO symbol_search_pending(entity_id, delete_text, insert_text, needs_delete)
    VALUES (OLD.id, OLD.name || char(10) || OLD.qualified_name, NULL, 1)
    ON CONFLICT(entity_id) DO UPDATE SET
        insert_text = NULL;
END;

CREATE VIRTUAL TABLE IF NOT EXISTS relation_search_fts USING fts5(
    search_text,
    tokenize='trigram',
    content='',
    detail='none',
    columnsize=0
);
INSERT INTO relation_search_fts(relation_search_fts) VALUES ('delete-all');
INSERT INTO relation_search_fts(rowid, search_text)
SELECT id, source_ref || char(10) || target_ref
FROM symbol_relations;

CREATE TABLE IF NOT EXISTS relation_search_pending (
    entity_id INTEGER PRIMARY KEY,
    delete_text TEXT,
    insert_text TEXT,
    needs_delete INTEGER NOT NULL CHECK (needs_delete IN (0, 1))
);
DELETE FROM relation_search_pending WHERE entity_id IS NOT NULL;

DROP TRIGGER IF EXISTS relations_search_pending_ai;
CREATE TRIGGER relations_search_pending_ai
AFTER INSERT ON symbol_relations
BEGIN
    INSERT INTO relation_search_pending(entity_id, delete_text, insert_text, needs_delete)
    VALUES (NEW.id, NULL, NEW.source_ref || char(10) || NEW.target_ref, 0)
    ON CONFLICT(entity_id) DO UPDATE SET
        insert_text = excluded.insert_text;

    INSERT INTO relation_search_fts(relation_search_fts, rowid, search_text)
    SELECT 'delete', entity_id, delete_text
    FROM relation_search_pending
    WHERE needs_delete = 1
      AND entity_id IN (
          SELECT entity_id FROM relation_search_pending ORDER BY entity_id LIMIT 5000
      )
      AND (SELECT COUNT(*) FROM relation_search_pending) >= 5000;

    INSERT INTO relation_search_fts(rowid, search_text)
    SELECT entity_id, insert_text
    FROM relation_search_pending
    WHERE insert_text IS NOT NULL
      AND entity_id IN (
          SELECT entity_id FROM relation_search_pending ORDER BY entity_id LIMIT 5000
      )
      AND (SELECT COUNT(*) FROM relation_search_pending) >= 5000
    ORDER BY entity_id;

    DELETE FROM relation_search_pending
    WHERE entity_id IN (
        SELECT entity_id FROM relation_search_pending ORDER BY entity_id LIMIT 5000
    ) AND (SELECT COUNT(*) FROM relation_search_pending) >= 5000;
END;

DROP TRIGGER IF EXISTS relations_search_pending_au;
CREATE TRIGGER relations_search_pending_au
AFTER UPDATE OF project_id, source_ref, target_ref ON symbol_relations
BEGIN
    INSERT INTO relation_search_pending(entity_id, delete_text, insert_text, needs_delete)
    VALUES (
        NEW.id,
        OLD.source_ref || char(10) || OLD.target_ref,
        NEW.source_ref || char(10) || NEW.target_ref,
        1
    )
    ON CONFLICT(entity_id) DO UPDATE SET
        insert_text = excluded.insert_text;
END;

DROP TRIGGER IF EXISTS relations_search_pending_ad;
CREATE TRIGGER relations_search_pending_ad
AFTER DELETE ON symbol_relations
BEGIN
    INSERT INTO relation_search_pending(entity_id, delete_text, insert_text, needs_delete)
    VALUES (OLD.id, OLD.source_ref || char(10) || OLD.target_ref, NULL, 1)
    ON CONFLICT(entity_id) DO UPDATE SET
        insert_text = NULL;
END;

-- Keep a generation row for every project created after V002 so all generation
-- bumps take the UPDATE path. This leaves one canonical projection-flush trigger
-- instead of duplicating the same body for INSERT and UPDATE generation events.
DROP TRIGGER IF EXISTS project_index_generation_row_ai;
CREATE TRIGGER project_index_generation_row_ai
AFTER INSERT ON projects
BEGIN
    INSERT OR IGNORE INTO project_index_generations(project_id, generation)
    VALUES (NEW.id, 0);
END;

-- A previous replay of V008 may have installed the now-obsolete INSERT flush.
DROP TRIGGER IF EXISTS search_projection_flush_generation_ai;

DROP TRIGGER IF EXISTS search_projection_flush_generation_au;
CREATE TRIGGER search_projection_flush_generation_au
AFTER UPDATE OF generation ON project_index_generations
BEGIN
    INSERT INTO symbol_search_fts(symbol_search_fts, rowid, search_text)
    SELECT 'delete', entity_id, delete_text
    FROM symbol_search_pending
    WHERE needs_delete = 1;
    INSERT INTO symbol_search_fts(rowid, search_text)
    SELECT entity_id, insert_text
    FROM symbol_search_pending
    WHERE insert_text IS NOT NULL
    ORDER BY entity_id;
    DELETE FROM symbol_search_pending WHERE entity_id IS NOT NULL;

    INSERT INTO relation_search_fts(relation_search_fts, rowid, search_text)
    SELECT 'delete', entity_id, delete_text
    FROM relation_search_pending
    WHERE needs_delete = 1;
    INSERT INTO relation_search_fts(rowid, search_text)
    SELECT entity_id, insert_text
    FROM relation_search_pending
    WHERE insert_text IS NOT NULL
    ORDER BY entity_id;
    DELETE FROM relation_search_pending WHERE entity_id IS NOT NULL;
END;
