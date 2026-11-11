-- La politique secret-redaction-v9 masque la valeur d'un élément XML ou d'une option dont le nom
-- composé se termine par une clé sensible (<db-password>, <db_password>, --api-key) et refuse
-- une telle valeur XML au-delà de sa borne : sous secret-redaction-v8, ces valeurs n'étaient ni
-- masquées ni refusées, et les termes lexicaux et les embeddings existants peuvent les contenir
-- en clair. Conserver les faits canoniques ; imposer le rebuild normal des dérivés.
INSERT OR IGNORE INTO project_index_generations(project_id, generation)
SELECT id, 0 FROM projects;

UPDATE project_index_generations
SET generation = generation + 1
WHERE project_id IN (SELECT id FROM projects);

UPDATE projects
SET index_status = 'NOT_INDEXED', last_indexed_at = NULL
WHERE id IN (SELECT project_id FROM project_index_generations);
