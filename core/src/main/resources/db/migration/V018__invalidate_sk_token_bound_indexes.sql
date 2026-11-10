-- La politique secret-redaction-v7 refuse un jeton sk-, sk-proj- ou sk-ant- dès 256 caractères de
-- corps, compté après le préfixe : sous secret-redaction-v6, un jeton sk- de 256 à 260 caractères de
-- corps (sk-ant- à 256) n'était ni masqué ni refusé, et les termes lexicaux et les embeddings
-- existants peuvent le contenir en clair.
-- Conserver les faits canoniques ; imposer le rebuild normal des dérivés.
INSERT OR IGNORE INTO project_index_generations(project_id, generation)
SELECT id, 0 FROM projects;

UPDATE project_index_generations
SET generation = generation + 1
WHERE project_id IN (SELECT id FROM projects);

UPDATE projects
SET index_status = 'NOT_INDEXED', last_indexed_at = NULL
WHERE id IN (SELECT project_id FROM project_index_generations);
