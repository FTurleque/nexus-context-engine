-- Indexed substring search for symbols and relations.
--
-- The embedded Xerial SQLite baseline is qualified by
-- SqliteIndexedSubstringSearchTest, which probes both FTS5 and the trigram
-- tokenizer before asserting query-plan usage. Queries with at least three
-- Unicode code points use these derived indexes; shorter compatibility queries
-- retain the legacy LIKE fallback in SqliteIndexRepository.

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

CREATE TRIGGER symbols_search_fts_ai
AFTER INSERT ON symbols
BEGIN
    INSERT INTO symbol_search_fts(rowid, name, qualified_name, project_id)
    SELECT NEW.id, NEW.name, NEW.qualified_name, f.project_id
    FROM indexed_files f
    WHERE f.id = NEW.file_id;
END;

CREATE TRIGGER symbols_search_fts_ad
AFTER DELETE ON symbols
BEGIN
    DELETE FROM symbol_search_fts WHERE rowid = OLD.id;
END;

CREATE TRIGGER symbols_search_fts_au
AFTER UPDATE OF file_id, name, qualified_name ON symbols
BEGIN
    DELETE FROM symbol_search_fts WHERE rowid = OLD.id;
    INSERT INTO symbol_search_fts(rowid, name, qualified_name, project_id)
    SELECT NEW.id, NEW.name, NEW.qualified_name, f.project_id
    FROM indexed_files f
    WHERE f.id = NEW.file_id;
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

CREATE TRIGGER relation_search_fts_ai
AFTER INSERT ON symbol_relations
BEGIN
    INSERT INTO relation_search_fts(rowid, source_ref, target_ref, project_id)
    VALUES (NEW.id, NEW.source_ref, NEW.target_ref, NEW.project_id);
END;

CREATE TRIGGER relation_search_fts_ad
AFTER DELETE ON symbol_relations
BEGIN
    DELETE FROM relation_search_fts WHERE rowid = OLD.id;
END;

CREATE TRIGGER relation_search_fts_au
AFTER UPDATE OF project_id, source_ref, target_ref ON symbol_relations
BEGIN
    DELETE FROM relation_search_fts WHERE rowid = OLD.id;
    INSERT INTO relation_search_fts(rowid, source_ref, target_ref, project_id)
    VALUES (NEW.id, NEW.source_ref, NEW.target_ref, NEW.project_id);
END;
