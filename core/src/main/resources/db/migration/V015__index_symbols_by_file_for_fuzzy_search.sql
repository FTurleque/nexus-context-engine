-- AUD-PRF-09 : le préfiltre approché des symboles (premier caractère et longueur du nom)
-- utilisait idx_symbols_fuzzy_prefilter, dont aucune colonne n'identifie le projet : la
-- requête parcourait les candidats de tous les projets avant de filtrer par projet, et son
-- coût croissait avec la taille de la base entière.
-- Le nouvel index commence par file_id : le projet cible fournit ses fichiers par
-- UNIQUE (project_id, relative_path), puis chaque fichier est sondé dans cet index, donc le
-- coût ne dépend que du projet ciblé. Il couvre aussi les recherches par file_id seul.
-- L'ancien index n'est plus utilisé par aucune requête : il est supprimé pour ne plus être
-- maintenu à chaque écriture de symbole (même motif que V013).
-- Aucune donnée canonique, aucune génération et aucun statut de projet ne sont modifiés :
-- aucun rebuild n'est imposé.
CREATE INDEX IF NOT EXISTS idx_symbols_file_fuzzy_prefilter
    ON symbols(file_id, SUBSTR(LOWER(name), 1, 1), LENGTH(name));

DROP INDEX IF EXISTS idx_symbols_fuzzy_prefilter;
