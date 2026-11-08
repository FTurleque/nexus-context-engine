-- AUD-PRF-01 : la suppression des relations d'un fichier par file_id et source_provider,
-- et la vérification de la cascade de clé étrangère depuis indexed_files, parcouraient
-- toute la table symbol_relations car tous ses index commencent par project_id.
-- Cet index évolutif rend le coût de réindexation proportionnel aux fichiers modifiés.
-- Aucune donnée canonique, aucune génération et aucun statut de projet ne sont modifiés :
-- aucun rebuild n'est imposé.
CREATE INDEX IF NOT EXISTS idx_symbol_relations_file_provider
    ON symbol_relations(file_id, source_provider);
