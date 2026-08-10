-- NXA2-01: CodeSymbol now requires start_line >= 1 and end_line >= start_line.
-- Historical databases may contain ranges such as -1/-1 from analyzers that had
-- no source range. Do not guess repaired coordinates: invalidate the complete
-- project index so the next indexing operation performs a deterministic rebuild.

INSERT OR IGNORE INTO project_index_generations(project_id, generation)
SELECT id, 0 FROM projects;

UPDATE projects
SET index_status = 'NOT_INDEXED',
    last_indexed_at = NULL
WHERE id IN (
    SELECT DISTINCT f.project_id
    FROM symbols s
    JOIN indexed_files f ON f.id = s.file_id
    WHERE s.start_line < 1 OR s.end_line < s.start_line
);

UPDATE project_index_generations
SET generation = generation + 1
WHERE project_id IN (
    SELECT DISTINCT f.project_id
    FROM symbols s
    JOIN indexed_files f ON f.id = s.file_id
    WHERE s.start_line < 1 OR s.end_line < s.start_line
);

DELETE FROM indexed_files
WHERE project_id IN (
    SELECT DISTINCT f.project_id
    FROM symbols s
    JOIN indexed_files f ON f.id = s.file_id
    WHERE s.start_line < 1 OR s.end_line < s.start_line
);
