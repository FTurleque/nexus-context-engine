CREATE TABLE project_index_generations (
    project_id TEXT PRIMARY KEY,
    generation INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
);

INSERT OR IGNORE INTO project_index_generations(project_id, generation)
SELECT id, 0 FROM projects;

CREATE INDEX idx_symbol_relations_source
    ON symbol_relations(project_id, source_ref);
