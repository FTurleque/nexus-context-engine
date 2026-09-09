-- Le codec historique confondait les composants POSIX contenant un backslash
-- avec des répertoires. Aucune reconstruction des anciennes identités n'est sûre.
-- Conserver les projets/configurations, supprimer les faits canoniques ambigus
-- (symboles et relations sont supprimés par les FK ON DELETE CASCADE).
DELETE FROM indexed_files;

INSERT OR IGNORE INTO project_index_generations(project_id, generation)
SELECT id, 0 FROM projects;

UPDATE project_index_generations SET generation = generation + 1;
UPDATE projects SET index_status = 'NOT_INDEXED', last_indexed_at = NULL;
