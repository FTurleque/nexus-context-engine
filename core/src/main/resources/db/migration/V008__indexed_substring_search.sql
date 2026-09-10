-- Indexed substring search for symbols and relations.
--
-- The embedded Xerial SQLite baseline is qualified by
-- SqliteIndexedSubstringSearchTest, which probes both FTS5 and the trigram
-- tokenizer before asserting query-plan usage. Queries with at least three
-- Unicode code points use these derived indexes; shorter compatibility queries
-- retain the legacy LIKE fallback in SqliteIndexRepository.
--
-- FTS5 is a derived projection. Canonical mutations are first staged in small
-- ordinary SQLite tables. Batches of 100 are flushed with INSERT ... SELECT,
-- avoiding one expensive FTS tokenization statement per canonical row. The
-- project generation bump performed at the end of every repository mutation
-- flushes the residual batch in the same transaction, so a committed generation
-- and its search projection remain atomic.

CREATE INDEX idx_symbols_fuzzy_prefilter
    ON symbols(SUBSTR(LOWER(name), 1, 1), LENGTH(name));

CREATE VIRTUAL TABLE symbol_search_fts USING fts5(
    name,
    qualified_name,
    project_id UNINDEXED,
    tokenize='trigram'
);

INSERT INTO symbol_search_fts(rowid, name, qualified_name, project_id)
SELECT s.id, s.name, s.qualified_name, f.project_id
FROM symbols s
JOIN indexed_files f ON f.id = s.file_id;

CREATE TABLE symbol_search_pending (
    entity_id INTEGER PRIMARY KEY,
    operation TEXT NOT NULL CHECK (operation IN ('UPSERT', 'DELETE')),
    name TEXT,
    qualified_name TEXT,
    project_id TEXT
);

CREATE TRIGGER symbols_search_pending_ai
AFTER INSERT ON symbols
BEGIN
    INSERT INTO symbol_search_pending(entity_id, operation, name, qualified_name, project_id)
    SELECT NEW.id, 'UPSERT', NEW.name, NEW.qualified_name, f.project_id
    FROM indexed_files f
    WHERE f.id = NEW.file_id
    ON CONFLICT(entity_id) DO UPDATE SET
        operation = excluded.operation,
        name = excluded.name,
        qualified_name = excluded.qualified_name,
        project_id = excluded.project_id;

    DELETE FROM symbol_search_fts
    WHERE rowid IN (
        SELECT entity_id FROM symbol_search_pending ORDER BY entity_id LIMIT 100
    ) AND (SELECT COUNT(*) FROM symbol_search_pending) >= 100;

    INSERT INTO symbol_search_fts(rowid, name, qualified_name, project_id)
    SELECT entity_id, name, qualified_name, project_id
    FROM symbol_search_pending
    WHERE operation = 'UPSERT'
      AND entity_id IN (
          SELECT entity_id FROM symbol_search_pending ORDER BY entity_id LIMIT 100
      )
      AND (SELECT COUNT(*) FROM symbol_search_pending) >= 100
    ORDER BY entity_id;

    DELETE FROM symbol_search_pending
    WHERE entity_id IN (
        SELECT entity_id FROM symbol_search_pending ORDER BY entity_id LIMIT 100
    ) AND (SELECT COUNT(*) FROM symbol_search_pending) >= 100;
END;

CREATE TRIGGER symbols_search_pending_au
AFTER UPDATE OF file_id, name, qualified_name ON symbols
BEGIN
    INSERT INTO symbol_search_pending(entity_id, operation, name, qualified_name, project_id)
    SELECT NEW.id, 'UPSERT', NEW.name, NEW.qualified_name, f.project_id
    FROM indexed_files f
    WHERE f.id = NEW.file_id
    ON CONFLICT(entity_id) DO UPDATE SET
        operation = excluded.operation,
        name = excluded.name,
        qualified_name = excluded.qualified_name,
        project_id = excluded.project_id;
END;

CREATE TRIGGER symbols_search_pending_ad
AFTER DELETE ON symbols
BEGIN
    INSERT INTO symbol_search_pending(entity_id, operation, name, qualified_name, project_id)
    VALUES (OLD.id, 'DELETE', NULL, NULL, NULL)
    ON CONFLICT(entity_id) DO UPDATE SET
        operation = 'DELETE',
        name = NULL,
        qualified_name = NULL,
        project_id = NULL;
END;

CREATE VIRTUAL TABLE relation_search_fts USING fts5(
    source_ref,
    target_ref,
    project_id UNINDEXED,
    tokenize='trigram'
);

INSERT INTO relation_search_fts(rowid, source_ref, target_ref, project_id)
SELECT id, source_ref, target_ref, project_id
FROM symbol_relations;

CREATE TABLE relation_search_pending (
    entity_id INTEGER PRIMARY KEY,
    operation TEXT NOT NULL CHECK (operation IN ('UPSERT', 'DELETE')),
    source_ref TEXT,
    target_ref TEXT,
    project_id TEXT
);

CREATE TRIGGER relations_search_pending_ai
AFTER INSERT ON symbol_relations
BEGIN
    INSERT INTO relation_search_pending(entity_id, operation, source_ref, target_ref, project_id)
    VALUES (NEW.id, 'UPSERT', NEW.source_ref, NEW.target_ref, NEW.project_id)
    ON CONFLICT(entity_id) DO UPDATE SET
        operation = excluded.operation,
        source_ref = excluded.source_ref,
        target_ref = excluded.target_ref,
        project_id = excluded.project_id;

    DELETE FROM relation_search_fts
    WHERE rowid IN (
        SELECT entity_id FROM relation_search_pending ORDER BY entity_id LIMIT 100
    ) AND (SELECT COUNT(*) FROM relation_search_pending) >= 100;

    INSERT INTO relation_search_fts(rowid, source_ref, target_ref, project_id)
    SELECT entity_id, source_ref, target_ref, project_id
    FROM relation_search_pending
    WHERE operation = 'UPSERT'
      AND entity_id IN (
          SELECT entity_id FROM relation_search_pending ORDER BY entity_id LIMIT 100
      )
      AND (SELECT COUNT(*) FROM relation_search_pending) >= 100
    ORDER BY entity_id;

    DELETE FROM relation_search_pending
    WHERE entity_id IN (
        SELECT entity_id FROM relation_search_pending ORDER BY entity_id LIMIT 100
    ) AND (SELECT COUNT(*) FROM relation_search_pending) >= 100;
END;

CREATE TRIGGER relations_search_pending_au
AFTER UPDATE OF project_id, source_ref, target_ref ON symbol_relations
BEGIN
    INSERT INTO relation_search_pending(entity_id, operation, source_ref, target_ref, project_id)
    VALUES (NEW.id, 'UPSERT', NEW.source_ref, NEW.target_ref, NEW.project_id)
    ON CONFLICT(entity_id) DO UPDATE SET
        operation = excluded.operation,
        source_ref = excluded.source_ref,
        target_ref = excluded.target_ref,
        project_id = excluded.project_id;
END;

CREATE TRIGGER relations_search_pending_ad
AFTER DELETE ON symbol_relations
BEGIN
    INSERT INTO relation_search_pending(entity_id, operation, source_ref, target_ref, project_id)
    VALUES (OLD.id, 'DELETE', NULL, NULL, OLD.project_id)
    ON CONFLICT(entity_id) DO UPDATE SET
        operation = 'DELETE',
        source_ref = NULL,
        target_ref = NULL,
        project_id = OLD.project_id;
END;

CREATE TRIGGER search_projection_flush_generation_ai
AFTER INSERT ON project_index_generations
BEGIN
    DELETE FROM symbol_search_fts
    WHERE rowid IN (SELECT entity_id FROM symbol_search_pending);
    INSERT INTO symbol_search_fts(rowid, name, qualified_name, project_id)
    SELECT entity_id, name, qualified_name, project_id
    FROM symbol_search_pending
    WHERE operation = 'UPSERT'
    ORDER BY entity_id;
    DELETE FROM symbol_search_pending;

    DELETE FROM relation_search_fts
    WHERE rowid IN (SELECT entity_id FROM relation_search_pending);
    INSERT INTO relation_search_fts(rowid, source_ref, target_ref, project_id)
    SELECT entity_id, source_ref, target_ref, project_id
    FROM relation_search_pending
    WHERE operation = 'UPSERT'
    ORDER BY entity_id;
    DELETE FROM relation_search_pending;
END;

CREATE TRIGGER search_projection_flush_generation_au
AFTER UPDATE OF generation ON project_index_generations
BEGIN
    DELETE FROM symbol_search_fts
    WHERE rowid IN (SELECT entity_id FROM symbol_search_pending);
    INSERT INTO symbol_search_fts(rowid, name, qualified_name, project_id)
    SELECT entity_id, name, qualified_name, project_id
    FROM symbol_search_pending
    WHERE operation = 'UPSERT'
    ORDER BY entity_id;
    DELETE FROM symbol_search_pending;

    DELETE FROM relation_search_fts
    WHERE rowid IN (SELECT entity_id FROM relation_search_pending);
    INSERT INTO relation_search_fts(rowid, source_ref, target_ref, project_id)
    SELECT entity_id, source_ref, target_ref, project_id
    FROM relation_search_pending
    WHERE operation = 'UPSERT'
    ORDER BY entity_id;
    DELETE FROM relation_search_pending;
END;
