-- Le codec historique confondait les composants POSIX contenant un backslash
-- avec des répertoires. Aucune reconstruction des anciennes identités n'est sûre.
-- Conserver les projets/configurations, supprimer les faits canoniques ambigus
-- (symboles et relations sont supprimés par les FK ON DELETE CASCADE).
DELETE FROM indexed_files
WHERE project_id IS NOT NULL;

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
SET index_status = 'NOT_INDEXED',
    last_indexed_at = NULL
WHERE EXISTS (
    SELECT 1
    FROM project_index_generations
    WHERE project_index_generations.project_id = projects.id
);
