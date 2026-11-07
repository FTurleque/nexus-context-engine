-- AUD-PRF-12 : table de mise en attente des analyses d'une indexation (ADR DATA-0062).
-- Les analyses (fichier, symboles, relations) des fichiers modifiés sont écrites par courtes
-- transactions au rythme des flushs de documents, puis appliquées aux tables canoniques et
-- publiées avec la génération par la transaction finale, qui vide ces tables. Le heap de
-- l'indexation est ainsi borné par lot et le verrou d'écriture SQLite n'est jamais tenu pendant
-- toute l'indexation.
-- Ces tables sont transitoires : elles ne contiennent aucun fait canonique, sont vides hors
-- indexation et sont vidées à l'ouverture d'une indexation. Aucune donnée existante, aucune
-- génération et aucun statut de projet ne sont modifiés : aucun rebuild n'est imposé.
CREATE TABLE IF NOT EXISTS index_staged_files (
    id INTEGER PRIMARY KEY,
    project_id TEXT NOT NULL,
    relative_path TEXT NOT NULL,
    language TEXT NOT NULL,
    size_bytes INTEGER NOT NULL,
    content_hash TEXT NOT NULL,
    modified_at TEXT NOT NULL,
    estimated_tokens INTEGER NOT NULL DEFAULT 0,
    category TEXT NOT NULL,
    UNIQUE (project_id, relative_path),
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS index_staged_symbols (
    id INTEGER PRIMARY KEY,
    staged_file_id INTEGER NOT NULL,
    kind TEXT NOT NULL,
    name TEXT NOT NULL,
    qualified_name TEXT NOT NULL,
    signature TEXT NOT NULL,
    start_line INTEGER NOT NULL CHECK (start_line >= 1),
    end_line INTEGER NOT NULL CHECK (end_line >= start_line),
    source_provider TEXT NOT NULL,
    FOREIGN KEY (staged_file_id) REFERENCES index_staged_files(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_index_staged_symbols_file
    ON index_staged_symbols(staged_file_id);

CREATE TABLE IF NOT EXISTS index_staged_relations (
    id INTEGER PRIMARY KEY,
    staged_file_id INTEGER NOT NULL,
    kind TEXT NOT NULL,
    source_ref TEXT NOT NULL,
    target_ref TEXT NOT NULL,
    confidence REAL NOT NULL DEFAULT 1.0,
    source_provider TEXT NOT NULL,
    FOREIGN KEY (staged_file_id) REFERENCES index_staged_files(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_index_staged_relations_file
    ON index_staged_relations(staged_file_id);
