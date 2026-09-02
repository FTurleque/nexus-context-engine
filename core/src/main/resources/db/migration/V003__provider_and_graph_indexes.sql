-- Provider projections: findExternalProviders joins symbols through indexed_files
-- and filters source_provider; the composite index avoids scanning every symbol
-- for each file once the project file ids have been selected.
CREATE INDEX IF NOT EXISTS idx_symbols_file_provider
    ON symbols(file_id, source_provider);

CREATE INDEX IF NOT EXISTS idx_symbol_relations_project_provider
    ON symbol_relations(project_id, source_provider);

-- Bounded graph-neighborhood projections always constrain project + relation kind
-- before matching a source or target reference. These indexes keep each hop local
-- even when the relation table contains millions of rows.
CREATE INDEX IF NOT EXISTS idx_symbol_relations_project_kind_source
    ON symbol_relations(project_id, kind, source_ref);

CREATE INDEX IF NOT EXISTS idx_symbol_relations_project_kind_target
    ON symbol_relations(project_id, kind, target_ref);
