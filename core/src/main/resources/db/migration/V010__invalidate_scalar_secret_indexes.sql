-- Les scalaires sensibles non cités et courts invalident les anciens termes et embeddings.
-- Conserver les faits canoniques ; imposer le rebuild normal des dérivés.
INSERT OR IGNORE INTO project_index_generations(project_id, generation)
SELECT id, 0 FROM projects;

UPDATE project_index_generations
SET generation = generation + 1
WHERE EXISTS (
    SELECT 1
    FROM projects
    WHERE projects.id = project_index_generations.project_id
);

UPDATE projects
SET index_status = 'NOT_INDEXED', last_indexed_at = NULL
WHERE EXISTS (
    SELECT 1
    FROM project_index_generations
    WHERE project_index_generations.project_id = projects.id
);
