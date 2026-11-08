-- AUD-PRF-15 : trois index sont des préfixes stricts d'autres index de la même table
-- et ne servent plus aucune requête. Chaque écriture de fichier les maintenait pour rien.
--   idx_symbol_relations_project (project_id) est servi par idx_symbol_relations_project_provider
--   idx_indexed_files_project (project_id) est servi par la contrainte UNIQUE (project_id, relative_path)
--   idx_symbols_file (file_id) est servi par idx_symbols_file_provider
-- SqliteGraphQueryPlanTest prouve, requête par requête, qu'un index plus large les sert.
-- Aucune donnée canonique, aucune génération et aucun statut de projet ne sont modifiés :
-- aucun rebuild n'est imposé. Une migration ultérieure peut recréer un index si besoin.
DROP INDEX IF EXISTS idx_symbol_relations_project;
DROP INDEX IF EXISTS idx_indexed_files_project;
DROP INDEX IF EXISTS idx_symbols_file;
