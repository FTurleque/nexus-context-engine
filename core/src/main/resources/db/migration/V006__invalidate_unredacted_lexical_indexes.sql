-- Security hardening: lexical Lucene documents created before V006 tokenized raw
-- repository content. Field.Store.NO prevented direct field retrieval but did not
-- remove analyzed terms from the on-disk inverted index.
--
-- The runtime now redacts sensitive content before Lucene tokenization. Force every
-- existing project through the normal full-rebuild path once so historical Lucene
-- segments are deleted and recreated from the redacted representation.

INSERT OR IGNORE INTO project_index_generations(project_id, generation)
SELECT id, 0 FROM projects;

UPDATE project_index_generations
SET generation = generation + 1;

UPDATE projects
SET index_status = 'NOT_INDEXED',
    last_indexed_at = NULL;
