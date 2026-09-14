package com.nexus.persistence.sqlite;

import com.nexus.config.NexusPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class SqliteReadSessionTest {
    @TempDir Path directory;

    @Test
    void reusesOneConnectionWithinNestedOperationAndClosesOnFailure() throws Exception {
        var db = new SqliteDatabase(new NexusPaths(directory.resolve("home")));
        Connection borrowed;
        try (var scope = db.openReadSession()) {
            borrowed = db.openConnection();
            borrowed.close();
            assertFalse(borrowed.isClosed());
            try (var nested = db.openReadSession(); var again = db.openConnection()) {
                assertSame(borrowed, again);
                assertTrue(again.getAutoCommit(), "Pas de snapshot transactionnel implicite");
            }
            assertFalse(borrowed.isClosed());
        }
        assertTrue(borrowed.isClosed());
        var failed = new Connection[1];
        assertThrows(IllegalStateException.class, () -> {
            try (var scope = db.openReadSession()) {
                failed[0] = db.openConnection();
                throw new IllegalStateException("fixture");
            }
        });
        assertTrue(failed[0].isClosed());
        try (var fresh = db.openConnection()) { assertNotSame(borrowed, fresh); }
    }

    @Test
    void concurrentThreadsAreIsolatedAndCommittedUpdatesRemainVisible() throws Exception {
        var db = new SqliteDatabase(new NexusPaths(directory.resolve("home")));
        db.writeTransaction("fixture", c -> { try (var s = c.createStatement()) {
            s.execute("CREATE TABLE session_test(value INTEGER)"); s.execute("INSERT INTO session_test VALUES(1)");
        }});
        try (var scope = db.openReadSession(); var first = db.openConnection();
             var worker = Executors.newSingleThreadExecutor()) {
            assertEquals(1, value(first));
            worker.submit(() -> {
                try (var otherScope = db.openReadSession(); var other = db.openConnection()) {
                    assertNotSame(first, other);
                    db.writeTransaction("update", c -> { try (var s = c.createStatement()) { s.execute("UPDATE session_test SET value=2"); }});
                    return null;
                }
            }).get();
            assertEquals(2, value(first), "Une connexion réutilisée ne cache pas les données");
        }
    }

    private static int value(Connection connection) throws Exception {
        try (var s = connection.createStatement(); var r = s.executeQuery("SELECT value FROM session_test")) {
            assertTrue(r.next()); return r.getInt(1);
        }
    }
}
