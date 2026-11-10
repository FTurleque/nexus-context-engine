-- La politique secret-redaction-v8 refuse un jeton à préfixe trop long collé à un bloc de clé
-- privée et compte le corps des jetons xox?-, gh?_, sk_ et rk_ sur la même classe de caractères
-- au masquage et au refus : sous secret-redaction-v7, un tel jeton n'était ni masqué ni refusé, et
-- les termes lexicaux et les embeddings existants peuvent le contenir en clair.
-- Conserver les faits canoniques ; imposer le rebuild normal des dérivés.
INSERT OR IGNORE INTO project_index_generations(project_id, generation)
SELECT id, 0 FROM projects;

UPDATE project_index_generations
SET generation = generation + 1
WHERE project_id IN (SELECT id FROM projects);

UPDATE projects
SET index_status = 'NOT_INDEXED', last_indexed_at = NULL
WHERE id IN (SELECT project_id FROM project_index_generations);
