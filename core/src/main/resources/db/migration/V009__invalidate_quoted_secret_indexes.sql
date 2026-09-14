-- La redaction des clés JSON citées invalide les anciens termes et embeddings.
-- Conserver les faits canoniques ; imposer le rebuild normal des dérivés.
INSERT OR IGNORE INTO project_index_generations(project_id, generation)
SELECT id, 0 FROM projects;

UPDATE project_index_generations SET generation = generation + 1;
UPDATE projects SET index_status = 'NOT_INDEXED', last_indexed_at = NULL;
