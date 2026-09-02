CREATE TABLE projects (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    root_path TEXT NOT NULL UNIQUE,
    source_type TEXT NOT NULL,
    last_indexed_at TEXT,
    index_status TEXT NOT NULL
);

CREATE TABLE project_languages (
    project_id TEXT NOT NULL,
    language TEXT NOT NULL,
    PRIMARY KEY (project_id, language),
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
);

CREATE TABLE project_technologies (
    project_id TEXT NOT NULL,
    technology TEXT NOT NULL,
    PRIMARY KEY (project_id, technology),
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
);

CREATE TABLE indexed_files (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
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

CREATE INDEX idx_indexed_files_project
    ON indexed_files(project_id);

CREATE INDEX idx_indexed_files_hash
    ON indexed_files(project_id, content_hash);

CREATE TABLE symbols (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    file_id INTEGER NOT NULL,
    kind TEXT NOT NULL,
    name TEXT NOT NULL,
    qualified_name TEXT NOT NULL,
    signature TEXT NOT NULL,
    start_line INTEGER NOT NULL,
    end_line INTEGER NOT NULL,
    source_provider TEXT NOT NULL,
    FOREIGN KEY (file_id) REFERENCES indexed_files(id) ON DELETE CASCADE
);

CREATE INDEX idx_symbols_file
    ON symbols(file_id);

CREATE INDEX idx_symbols_name
    ON symbols(name);

CREATE INDEX idx_symbols_qualified_name
    ON symbols(qualified_name);

CREATE TABLE symbol_relations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id TEXT NOT NULL,
    file_id INTEGER NOT NULL,
    kind TEXT NOT NULL,
    source_ref TEXT NOT NULL,
    target_ref TEXT NOT NULL,
    confidence REAL NOT NULL DEFAULT 1.0,
    source_provider TEXT NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    FOREIGN KEY (file_id) REFERENCES indexed_files(id) ON DELETE CASCADE
);

CREATE INDEX idx_symbol_relations_project
    ON symbol_relations(project_id);

CREATE INDEX idx_symbol_relations_target
    ON symbol_relations(project_id, target_ref);
