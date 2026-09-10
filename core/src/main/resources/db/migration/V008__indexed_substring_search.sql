-- Indexed substring search for symbols and relations.
--
-- The embedded Xerial SQLite baseline is qualified by
-- SqliteIndexedSubstringSearchTest, which probes both FTS5 and the trigram
-- tokenizer before asserting query-plan usage. Queries with at least three
-- Unicode code points use these derived indexes; shorter compatibility queries
-- retain the legacy LIKE fallback in SqliteIndexRepository.
--
-- These FTS tables are derived projections. Existing canonical rows are
-- backfilled here; subsequent application writes synchronize them in the same
-- SQLite transaction through SqliteSearchIndexProjection. Avoiding per-row FTS
-- triggers keeps large batched indexing workloads bounded.

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

CREATE VIRTUAL TABLE relation_search_fts USING fts5(
    source_ref,
    target_ref,
    project_id UNINDEXED,
    tokenize='trigram'
);

INSERT INTO relation_search_fts(rowid, source_ref, target_ref, project_id)
SELECT id, source_ref, target_ref, project_id
FROM symbol_relations;
