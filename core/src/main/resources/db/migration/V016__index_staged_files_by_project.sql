-- AUD-PRF-27 : la transaction de publication d'une indexation relit index_staged_files par pages
-- (WHERE project_id = ? AND id > ? ORDER BY id LIMIT ?). Le seul index de la table était la
-- contrainte UNIQUE (project_id, relative_path) : chaque page relisait toutes les lignes du projet
-- puis les triait dans un arbre temporaire, pour un coût quadratique en nombre de fichiers, sous le
-- verrou d'écriture SQLite.
-- L'index (project_id, id) fournit l'intervalle et l'ordre de pagination : chaque page est une
-- recherche d'intervalle de coût proportionnel à sa taille. (project_id) seul serait un préfixe strict
-- de la contrainte UNIQUE ; id est l'alias du rowid.
-- Aucune donnée canonique, aucune génération et aucun statut de projet ne sont modifiés :
-- aucun rebuild n'est imposé.
CREATE INDEX IF NOT EXISTS idx_index_staged_files_project_id
    ON index_staged_files(project_id, id);
