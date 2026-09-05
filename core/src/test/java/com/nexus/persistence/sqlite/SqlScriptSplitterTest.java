package com.nexus.persistence.sqlite;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlScriptSplitterTest {

    @Test
    void ignoresSeparatorsInsideQuotesAndComments() {
        String script = """
                -- commentaire avec ; séparateur trompeur
                CREATE TABLE "semi;table" (`value;name` TEXT, [other;name] TEXT);
                /* commentaire bloc ; avec 'quotes' */
                INSERT INTO "semi;table" VALUES ('a;''b', "quoted;value");
                """;

        List<String> statements = SqlScriptSplitter.split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("\"semi;table\""));
        assertTrue(statements.get(1).contains("'a;''b'"));
    }

    @Test
    void keepsCreateTriggerBodyTogetherAndExecutesIt() throws Exception {
        String script = """
                CREATE TABLE source(value TEXT);
                CREATE TABLE audit(value TEXT);
                CREATE TRIGGER audit_insert AFTER INSERT ON source
                BEGIN
                    INSERT INTO audit(value)
                    VALUES (CASE WHEN NEW.value = 'x;y' THEN 'matched;value' ELSE 'other' END);
                    UPDATE source SET value = value || ';done' WHERE rowid = NEW.rowid;
                END;
                """;

        List<String> statements = SqlScriptSplitter.split(script);
        assertEquals(3, statements.size());
        assertTrue(statements.get(2).startsWith("CREATE TRIGGER"));
        assertTrue(statements.get(2).contains("matched;value"));

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            for (String sql : statements) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO source(value) VALUES ('x;y')");
                try (ResultSet source = statement.executeQuery("SELECT value FROM source")) {
                    assertTrue(source.next());
                    assertEquals("x;y;done", source.getString(1));
                }
                try (ResultSet audit = statement.executeQuery("SELECT value FROM audit")) {
                    assertTrue(audit.next());
                    assertEquals("matched;value", audit.getString(1));
                }
            }
        }
    }

    @Test
    void rejectsUnterminatedBlockComments() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SqlScriptSplitter.split("CREATE TABLE sample(id INTEGER); /* unterminated"));
    }
}
