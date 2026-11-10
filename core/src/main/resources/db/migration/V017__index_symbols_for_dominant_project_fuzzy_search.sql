-- AUD-PRF-29 : le préfiltre approché scopé par fichier (V015) sonde idx_symbols_file_fuzzy_prefilter une
-- fois par fichier du projet ciblé. Quand ce projet tient l'essentiel de la base, ce parcours coûte plus
-- cher que l'intervalle de l'index global (premier caractère, longueur) supprimé par V015 : à 100 000
-- fichiers, un projet seul compte ses candidats 2,3 fois et les récupère 5,6 fois plus lentement
-- (relevé docs/project/history/recherche-approchee-projet-seul-2026-11-10.md).
-- L'index global est recréé ; SqliteSymbolSearch le force quand le projet ciblé possède au moins la
-- moitié des fichiers indexés et garde l'index scopé par fichier sinon, donc le coût d'un petit projet
-- reste indépendant des autres projets de la base.
-- Aucune donnée canonique, aucune génération et aucun statut de projet ne sont modifiés :
-- aucun rebuild n'est imposé.
CREATE INDEX IF NOT EXISTS idx_symbols_fuzzy_prefilter
    ON symbols(SUBSTR(LOWER(name), 1, 1), LENGTH(name));
