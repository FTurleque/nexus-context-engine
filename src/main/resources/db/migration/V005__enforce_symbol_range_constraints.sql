-- Rebuild symbols so SQLite enforces the same invariants as CodeSymbol.
-- V004 invalidated and removed any legacy project index containing invalid rows,
-- therefore every row copied here is expected to satisfy these constraints.
CREATE TABLE symbols_v005 (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    file_id INTEGER NOT NULL,
    kind TEXT NOT NULL,
    name TEXT NOT NULL,
    qualified_name TEXT NOT NULL,
    signature TEXT NOT NULL,
    start_line INTEGER NOT NULL CHECK (start_line >= 1),
    end_line INTEGER NOT NULL CHECK (end_line >= start_line),
    source_provider TEXT NOT NULL,
    FOREIGN KEY (file_id) REFERENCES indexed_files(id) ON DELETE CASCADE
);

INSERT INTO symbols_v005(
    id, file_id, kind, name, qualified_name, signature,
    start_line, end_line, source_provider)
SELECT
    id, file_id, kind, name, qualified_name, signature,
    start_line, end_line, source_provider
FROM symbols;

DROP TABLE symbols;
ALTER TABLE symbols_v005 RENAME TO symbols;

CREATE INDEX idx_symbols_file ON symbols(file_id);
CREATE INDEX idx_symbols_name ON symbols(name);
CREATE INDEX idx_symbols_qualified_name ON symbols(qualified_name);
CREATE INDEX idx_symbols_file_provider ON symbols(file_id, source_provider);
