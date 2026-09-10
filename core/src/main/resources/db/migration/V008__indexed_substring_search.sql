-- Indexed substring search for symbols and relations.
--
-- The embedded Xerial SQLite baseline is qualified by
-- SqliteIndexedSubstringSearchTest, which probes FTS5, the trigram tokenizer
-- and contentless-delete support before asserting query-plan usage. Queries
-- with at least three Unicode code points use these derived indexes; shorter
-- compatibility queries retain the legacy LIKE fallback in
-- SqliteIndexRepository.
--
-- The FTS tables are contentless and detail-free: SQLite stores only the
-- trigram posting lists required for candidate discovery, while canonical
-- values remain in symbols / symbol_relations. Canonical mutations are staged
-- in small ordinary tables. Batches of 5 000 are flushed with INSERT ... SELECT
-- and the project generation bump flushes the residual batch in the same
-- transaction.
--
-- The migration is replay-safe for the legacy checksum-table recovery test.
-- Derived structures are rebuilt deterministically when V008 is replayed.

CREATE INDEX IF NOT EXISTS idx_symbols_fuzzy_prefilter
    ON symbols(SUBSTR(LOWER(name), 1, 1), qualified_name, LENGTH(name));

CREATE VIRTUAL TABLE IF NOT EXISTS symbol_search_fts USING fts5(
    search_text,
    tokenize='trigram',
    content='',
    contentless_delete=1,
    detail='none'
);

DELETE FROM symbol_search_fts;
INSERT INTO symbol_search_fts(rowid, search_text)
SELECT s.id, s.name || char(10) || s.qualified_name
FROM symbols s;

CREATE TABLE IF NOT EXISTS symbol_search_pending (
    entity_id INTEGER PRIMARY KEY,
    operation TEXT NOT NULL CHECK (operation IN ('UPSERT', 'DELETE')),
    search_text TEXT
);
DELETE FROM symbol_search_pending;

DROP TRIGGER IF EXISTS symbols_search_pending_ai;
CREATE TRIGGER symbols_search_pending_ai
AFTER INSERT ON symbols
BEGIN
    INSERT INTO symbol_search_pending(entity_id, operation, search_text)
    VALUES (NEW.id, 'UPSERT', NEW.name || char(10) || NEW.qualified_name)
    ON CONFLICT(entity_id) DO UPDATE SET
        operation = excluded.operation,
        search_text = excluded.search_text;

    DELETE FROM symbol_search_fts
    WHERE rowid IN (
        SELECT entity_id FROM symbol_search_pending ORDER BY entity_id LIMIT 5000
    ) AND (SELECT COUNT(*) FROM symbol_search_pending) >= 5000;

    INSERT INTO symbol_search_fts(rowid, search_text)
    SELECT entity_id, search_text
    FROM symbol_search_pending
    WHERE operation = 'UPSERT'
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
    INSERT INTO symbol_search_pending(entity_id, operation, search_text)
    VALUES (NEW.id, 'UPSERT', NEW.name || char(10) || NEW.qualified_name)
    ON CONFLICT(entity_id) DO UPDATE SET
        operation = excluded.operation,
        search_text = excluded.search_text;
END;

DROP TRIGGER IF EXISTS symbols_search_pending_ad;
CREATE TRIGGER symbols_search_pending_ad
AFTER DELETE ON symbols
BEGIN
    INSERT INTO symbol_search_pending(entity_id, operation, search_text)
    VALUES (OLD.id, 'DELETE', NULL)
    ON CONFLICT(entity_id) DO UPDATE SET
        operation = 'DELETE',
        search_text = NULL;
END;

CREATE VIRTUAL TABLE IF NOT EXISTS relation_search_fts USING fts5(
    search_text,
    tokenize='trigram',
    content='',
    contentless_delete=1,
    detail='none'
);

DELETE FROM relation_search_fts;
INSERT INTO relation_search_fts(rowid, search_text)
SELECT id, source_ref || char(10) || target_ref
FROM symbol_relations;

CREATE TABLE IF NOT EXISTS relation_search_pending (
    entity_id INTEGER PRIMARY KEY,
    operation TEXT NOT NULL CHECK (operation IN ('UPSERT', 'DELETE')),
    search_text TEXT
);
DELETE FROM relation_search_pending;

DROP TRIGGER IF EXISTS relations_search_pending_ai;
CREATE TRIGGER relations_search_pending_ai
AFTER INSERT ON symbol_relations
BEGIN
    INSERT INTO relation_search_pending(entity_id, operation, search_text)
    VALUES (NEW.id, 'UPSERT', NEW.source_ref || char(10) || NEW.target_ref)
    ON CONFLICT(entity_id) DO UPDATE SET
        operation = excluded.operation,
        search_text = excluded.search_text;

    DELETE FROM relation_search_fts
    WHERE rowid IN (
        SELECT entity_id FROM relation_search_pending ORDER BY entity_id LIMIT 5000
    ) AND (SELECT COUNT(*) FROM relation_search_pending) >= 5000;

    INSERT INTO relation_search_fts(rowid, search_text)
    SELECT entity_id, search_text
    FROM relation_search_pending
    WHERE operation = 'UPSERT'
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
    INSERT INTO relation_search_pending(entity_id, operation, search_text)
    VALUES (NEW.id, 'UPSERT', NEW.source_ref || char(10) || NEW.target_ref)
    ON CONFLICT(entity_id) DO UPDATE SET
        operation = excluded.operation,
        search_text = excluded.search_text;
END;

DROP TRIGGER IF EXISTS relations_search_pending_ad;
CREATE TRIGGER relations_search_pending_ad
AFTER DELETE ON symbol_relations
BEGIN
    INSERT INTO relation_search_pending(entity_id, operation, search_text)
    VALUES (OLD.id, 'DELETE', NULL)
    ON CONFLICT(entity_id) DO UPDATE SET
        operation = 'DELETE',
        search_text = NULL;
END;

DROP TRIGGER IF EXISTS search_projection_flush_generation_ai;
CREATE TRIGGER search_projection_flush_generation_ai
AFTER INSERT ON project_index_generations
BEGIN
    DELETE FROM symbol_search_fts
    WHERE rowid IN (SELECT entity_id FROM symbol_search_pending);
    INSERT INTO symbol_search_fts(rowid, search_text)
    SELECT entity_id, search_text
    FROM symbol_search_pending
    WHERE operation = 'UPSERT'
    ORDER BY entity_id;
    DELETE FROM symbol_search_pending;

    DELETE FROM relation_search_fts
    WHERE rowid IN (SELECT entity_id FROM relation_search_pending);
    INSERT INTO relation_search_fts(rowid, search_text)
    SELECT entity_id, search_text
    FROM relation_search_pending
    WHERE operation = 'UPSERT'
    ORDER BY entity_id;
    DELETE FROM relation_search_pending;
END;

DROP TRIGGER IF EXISTS search_projection_flush_generation_au;
CREATE TRIGGER search_projection_flush_generation_au
AFTER UPDATE OF generation ON project_index_generations
BEGIN
    DELETE FROM symbol_search_fts
    WHERE rowid IN (SELECT entity_id FROM symbol_search_pending);
    INSERT INTO symbol_search_fts(rowid, search_text)
    SELECT entity_id, search_text
    FROM symbol_search_pending
    WHERE operation = 'UPSERT'
    ORDER BY entity_id;
    DELETE FROM symbol_search_pending;

    DELETE FROM relation_search_fts
    WHERE rowid IN (SELECT entity_id FROM relation_search_pending);
    INSERT INTO relation_search_fts(rowid, search_text)
    SELECT entity_id, search_text
    FROM relation_search_pending
    WHERE operation = 'UPSERT'
    ORDER BY entity_id;
    DELETE FROM relation_search_pending;
END;
