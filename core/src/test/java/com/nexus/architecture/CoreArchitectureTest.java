package com.nexus.architecture;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Frontières du cœur {@code com.nexus} (ADR-0004, ADR-0003, ADR-0006, ADR-0007), vérifiées sur
 * les sources de production de {@code core/src/main/java}.
 *
 * <p>Chaque règle a un cas négatif sur une fixture de sources écrite dans un répertoire
 * temporaire, pour prouver qu'elle échoue sur l'import qu'elle interdit.</p>
 */
class CoreArchitectureTest {

    private static final Path CORE_SOURCES = Path.of("core", "src", "main", "java");
    private static final String CYCLE_BASELINE = "/architecture/package-cycles.txt";

    private static JavaSourceGraph core;

    @TempDir
    Path fixture;

    @BeforeAll
    static void scanCoreSources() {
        assertTrue(Files.isDirectory(CORE_SOURCES), "Sources du cœur introuvables : " + CORE_SOURCES);
        core = JavaSourceGraph.scan(CORE_SOURCES, CoreArchitectureRules.WATCHED_QUALIFIED_PREFIXES);
        assertFalse(core.files().isEmpty(), "Aucune source Java lue sous " + CORE_SOURCES);
    }

    @Test
    void coreImportsNoApplicationFramework() {
        assertNoViolation(CoreArchitectureRules.coreWithoutFramework(core));
    }

    @Test
    void luceneIsConfinedToItsAdapters() {
        assertNoViolation(CoreArchitectureRules.luceneConfined(core));
    }

    @Test
    void sqliteAndJdbcAreConfinedToPersistence() {
        assertNoViolation(CoreArchitectureRules.sqliteConfined(core));
    }

    @Test
    void projectPackageIsALeaf() {
        assertNoViolation(CoreArchitectureRules.projectLeaf(core));
    }

    @Test
    void packageCyclesMatchTheBaseline() throws IOException {
        assertNoViolation(CoreArchitectureRules.cycleBaseline(core.cycleEdges(), readCycleBaseline()));
    }

    @Test
    void cycleBaselineIsSorted() throws IOException {
        List<String> lines = baselineLines();
        assertEquals(List.copyOf(new TreeSet<>(lines)), lines,
                "La baseline " + CYCLE_BASELINE + " doit être triée, sans doublon");
    }

    @Test
    void frameworkImportFailsCoreWithoutFramework() throws IOException {
        writeSource("com/nexus/search/Indexer.java", """
                package com.nexus.search;

                import io.quarkus.runtime.Startup;

                class Indexer {
                }
                """);

        assertEquals(List.of("[core-sans-framework] com.nexus.search.Indexer -> io.quarkus.runtime.Startup"),
                CoreArchitectureRules.coreWithoutFramework(scanFixture()));
    }

    @Test
    void luceneImportOutsideItsPackagesFailsLuceneConfined() throws IOException {
        writeSource("com/nexus/ranking/Scorer.java", """
                package com.nexus.ranking;

                import org.apache.lucene.search.Query;

                class Scorer {
                }
                """);
        writeSource("com/nexus/search/lucene/LuceneIndex.java", """
                package com.nexus.search.lucene;

                import org.apache.lucene.search.Query;

                class LuceneIndex {
                }
                """);

        assertEquals(List.of("[lucene-confine] com.nexus.ranking.Scorer -> org.apache.lucene.search.Query"),
                CoreArchitectureRules.luceneConfined(scanFixture()));
    }

    @Test
    void qualifiedLuceneNameWithoutImportFailsLuceneConfined() throws IOException {
        writeSource("com/nexus/ranking/Scorer.java", """
                package com.nexus.ranking;

                class Scorer {
                    Object document() {
                        return new org.apache.lucene.document.Document();
                    }
                }
                """);

        assertEquals(List.of("[lucene-confine] com.nexus.ranking.Scorer -> org.apache.lucene.document.Document"),
                CoreArchitectureRules.luceneConfined(scanFixture()));
    }

    @Test
    void jdbcImportInContextFailsSqliteConfined() throws IOException {
        writeSource("com/nexus/context/Loader.java", """
                package com.nexus.context;

                import java.sql.Connection;

                class Loader {
                }
                """);

        assertEquals(List.of("[sqlite-confine] com.nexus.context.Loader -> java.sql.Connection"),
                CoreArchitectureRules.sqliteConfined(scanFixture()));
    }

    @Test
    void closedSqlExceptionListPassesSqliteConfinedButNothingElse() throws IOException {
        writeSource("com/nexus/application/NexusApplicationFactory.java", """
                package com.nexus.application;

                import java.sql.SQLException;

                class NexusApplicationFactory {
                }
                """);
        writeSource("com/nexus/application/OtherFactory.java", """
                package com.nexus.application;

                import java.sql.SQLException;

                class OtherFactory {
                }
                """);
        writeSource("com/nexus/cli/NexusCli.java", """
                package com.nexus.cli;

                import java.sql.Connection;

                class NexusCli {
                }
                """);

        assertEquals(List.of(
                        "[sqlite-confine] com.nexus.application.OtherFactory -> java.sql.SQLException",
                        "[sqlite-confine] com.nexus.cli.NexusCli -> java.sql.Connection"),
                CoreArchitectureRules.sqliteConfined(scanFixture()));
    }

    @Test
    void upwardImportFromProjectFailsProjectLeaf() throws IOException {
        writeSource("com/nexus/project/ProjectId.java", """
                package com.nexus.project;

                import com.nexus.index.IndexRepository;
                import com.nexus.project.model.Name;

                class ProjectId {
                }
                """);

        assertEquals(List.of("[project-feuille] com.nexus.project.ProjectId -> com.nexus.index.IndexRepository"),
                CoreArchitectureRules.projectLeaf(scanFixture()));
    }

    @Test
    void newCycleEdgeFailsTheBaselineAndNamesThePackages() throws IOException {
        writeCycleFixture();

        List<String> violations = CoreArchitectureRules.cycleBaseline(scanFixture().cycleEdges(),
                new TreeSet<>(List.of("com.nexus.a -> com.nexus.b")));

        assertEquals(List.of("[cycles-baseline] nouvelle arête de cycle entre packages : com.nexus.b -> com.nexus.a"),
                violations);
    }

    @Test
    void staleBaselineEntryFailsAndAsksForItsRemoval() throws IOException {
        writeSource("com/nexus/a/A.java", """
                package com.nexus.a;

                import com.nexus.b.B;

                class A {
                }
                """);
        writeSource("com/nexus/b/B.java", """
                package com.nexus.b;

                class B {
                }
                """);

        List<String> violations = CoreArchitectureRules.cycleBaseline(scanFixture().cycleEdges(),
                new TreeSet<>(List.of("com.nexus.a -> com.nexus.b")));

        assertEquals(List.of("[cycles-baseline] l'arête com.nexus.a -> com.nexus.b n'appartient plus à un cycle :"
                + " retirer l'entrée de la baseline"), violations);
    }

    @Test
    void cycleEdgesKeepOnlyEdgesInsideAStronglyConnectedComponent() throws IOException {
        writeCycleFixture();
        writeSource("com/nexus/c/C.java", """
                package com.nexus.c;

                import com.nexus.a.A;

                class C {
                }
                """);

        assertEquals(new TreeSet<>(List.of("com.nexus.a -> com.nexus.b", "com.nexus.b -> com.nexus.a")),
                scanFixture().cycleEdges());
    }

    @Test
    void parserReadsStaticWildcardAndQualifiedNamesAndIgnoresCommentsAndStrings() {
        String source = """
                /* import org.apache.lucene.search.Ignored; */
                package com.nexus.ranking;

                // import java.sql.Connection;
                import static org.apache.lucene.util.Constants.JRE_IS_64BIT;
                import com.nexus.index . *;
                import java.util.List;

                /**
                 * Javadoc qui cite {@link org.apache.lucene.search.Query}.
                 */
                class Scorer {
                    String text = "import java.sql.Statement; org.sqlite.SQLiteConfig";
                    String block = \"""
                            org.apache.lucene.index.Term
                            \""";
                    char quote = '"';
                    Object value = java.sql.Types.VARCHAR;
                }
                """;

        JavaSourceGraph.SourceFile file = JavaSourceGraph.parse(Path.of("com", "nexus", "ranking", "Scorer.java"),
                source, CoreArchitectureRules.WATCHED_QUALIFIED_PREFIXES);

        assertEquals("com.nexus.ranking", file.packageName());
        assertEquals("com.nexus.ranking.Scorer", file.className());
        assertEquals(List.of("org.apache.lucene.util.Constants.JRE_IS_64BIT", "com.nexus.index.*", "java.util.List"),
                file.imports());
        assertEquals(List.of("java.sql.Types.VARCHAR"), file.qualifiedReferences());
    }

    private void writeCycleFixture() throws IOException {
        writeSource("com/nexus/a/A.java", """
                package com.nexus.a;

                import com.nexus.b.B;

                class A {
                }
                """);
        writeSource("com/nexus/b/B.java", """
                package com.nexus.b;

                import com.nexus.a.A;

                class B {
                }
                """);
    }

    private void writeSource(String relativePath, String content) throws IOException {
        Path target = fixture.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private JavaSourceGraph scanFixture() {
        return JavaSourceGraph.scan(fixture, CoreArchitectureRules.WATCHED_QUALIFIED_PREFIXES);
    }

    private static SortedSet<String> readCycleBaseline() throws IOException {
        return new TreeSet<>(baselineLines());
    }

    /** Lignes utiles de la baseline : ni vides, ni commentaires {@code #}. */
    private static List<String> baselineLines() throws IOException {
        try (InputStream input = CoreArchitectureTest.class.getResourceAsStream(CYCLE_BASELINE)) {
            assertNotNull(input, "Baseline de cycles introuvable : " + CYCLE_BASELINE);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        }
    }

    private static void assertNoViolation(List<String> violations) {
        assertTrue(violations.isEmpty(), () -> violations.size() + " violation(s) :\n" + String.join("\n", violations));
    }
}
