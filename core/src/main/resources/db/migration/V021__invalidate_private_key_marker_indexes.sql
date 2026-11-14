-- La politique secret-redaction-v10 cherche la fin d'un marqueur -----BEGIN sur sa ligne et reprend
-- la recherche juste après un marqueur qui n'ouvre pas de clé privée : sous secret-redaction-v9, un
-- marqueur incomplet, un littéral de code ou un marqueur d'un autre type collé au sien faisait sauter
-- le bloc de clé privée suivant, laissé en clair, ou masquait à tort tout le reste du contenu. Les
-- termes lexicaux et les embeddings existants peuvent contenir ce corps de clé en clair.
-- Conserver les faits canoniques ; imposer le rebuild normal des dérivés.
INSERT OR IGNORE INTO project_index_generations(project_id, generation)
SELECT id, 0 FROM projects;

UPDATE project_index_generations
SET generation = generation + 1
WHERE project_id IN (SELECT id FROM projects);

UPDATE projects
SET index_status = 'NOT_INDEXED', last_indexed_at = NULL
WHERE id IN (SELECT project_id FROM project_index_generations);
