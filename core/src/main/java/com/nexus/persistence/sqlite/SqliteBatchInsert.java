package com.nexus.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Parameterized multi-row inserts; transaction ownership stays with the caller. */
public final class SqliteBatchInsert implements AutoCloseable {
    public enum Table {
        SYMBOLS("INSERT INTO symbols(file_id,kind,name,qualified_name,signature,start_line,end_line,source_provider) VALUES ", 8),
        RELATIONS("INSERT INTO symbol_relations(project_id,file_id,kind,source_ref,target_ref,confidence,source_provider) VALUES ", 7);

        private final String prefix;
        private final int columns;

        Table(String prefix, int columns) {
            this.prefix = prefix;
            this.columns = columns;
        }
    }

    private static final int ROW_LIMIT = 128;
    private final Connection connection;
    private final Table table;
    private final List<Object[]> rows = new ArrayList<>(ROW_LIMIT);
    private PreparedStatement fullBatch;
    private boolean closed;

    public SqliteBatchInsert(Connection connection, Table table) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.table = Objects.requireNonNull(table, "table");
    }

    public void add(Object... values) throws SQLException {
        ensureOpen();
        if (values.length != table.columns) {
            throw new IllegalArgumentException("Unexpected column count for " + table);
        }
        rows.add(values.clone());
        if (rows.size() == ROW_LIMIT) {
            if (fullBatch == null) {
                fullBatch = prepare(ROW_LIMIT);
            }
            execute(fullBatch);
        }
    }

    public void flush() throws SQLException {
        ensureOpen();
        if (rows.isEmpty()) {
            return;
        }
        try (PreparedStatement tail = prepare(rows.size())) {
            execute(tail);
        }
    }

    @SuppressWarnings("java:S2077") // Only enum-owned SQL and bounded '?' tuples; values are bound below.
    private PreparedStatement prepare(int count) throws SQLException {
        String tuple = "(" + String.join(",", Collections.nCopies(table.columns, "?")) + ")";
        return connection.prepareStatement(table.prefix + String.join(",", Collections.nCopies(count, tuple)));
    }

    private void execute(PreparedStatement statement) throws SQLException {
        int index = 1;
        for (Object[] row : rows) {
            for (Object value : row) {
                statement.setObject(index++, value);
            }
        }
        statement.executeUpdate();
        rows.clear();
    }

    private void ensureOpen() throws SQLException {
        if (closed) {
            throw new SQLException("SQLite insert batch is closed");
        }
    }

    @Override
    public void close() throws SQLException {
        closed = true;
        // Never implicitly flush while unwinding a failed transaction.
        rows.clear();
        if (fullBatch != null) {
            fullBatch.close();
        }
    }
}
