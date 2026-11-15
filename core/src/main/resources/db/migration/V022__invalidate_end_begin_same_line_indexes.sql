-- La politique secret-redaction-v11 arrête un bloc de clé privée avant le marqueur -----BEGIN d'une clé
-- privée qui suit son -----END sur la même ligne, tirets partagés compris : sous secret-redaction-v10,
-- le curseur passait au-delà de ce marqueur et le corps de la seconde clé restait en clair. Les termes
-- lexicaux et les embeddings existants peuvent contenir ce corps de clé en clair.
-- Conserver les faits canoniques ; imposer le rebuild normal des dérivés.
INSERT OR IGNORE INTO project_index_generations(project_id, generation)
SELECT id, 0 FROM projects;

UPDATE project_index_generations
SET generation = generation + 1
WHERE project_id IN (SELECT id FROM projects);

UPDATE projects
SET index_status = 'NOT_INDEXED', last_indexed_at = NULL
WHERE id IN (SELECT project_id FROM project_index_generations);
