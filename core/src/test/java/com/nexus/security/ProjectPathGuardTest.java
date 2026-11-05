package com.nexus.security;

import com.nexus.paths.RepositoryPath;
import com.nexus.testsupport.SymbolicLinks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests directs de la primitive de confinement : chaque méthode publique face au
 * traversal, au chemin absolu, aux liens symboliques final et intermédiaire, au type
 * attendu et à la cible hors racine.
 */
class ProjectPathGuardTest {

    @TempDir
    Path temporaryDirectory;

    private Path project;
    private Path outside;
    private ProjectPathGuard guard;

    @BeforeEach
    void createFixture() throws IOException {
        project = Files.createDirectories(temporaryDirectory.resolve("project"));
        Files.createDirectories(project.resolve("src").resolve("main"));
        Files.writeString(project.resolve("src").resolve("main").resolve("App.java"), "class App {}");
        outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
        Files.createDirectories(outside.resolve("sub"));
        Files.writeString(outside.resolve("secret.txt"), "outside-secret");
        guard = new ProjectPathGuard(project);
    }

    private Path app() {
        return guard.root().resolve("src").resolve("main").resolve("App.java");
    }

    // --- constructeur / racine ---

    @Test
    void canonicalisesTheProjectRoot() throws IOException {
        assertEquals(project.toRealPath(), guard.root());
    }

    @Test
    void rejectsARootThatIsARegularFile() throws IOException {
        Path file = Files.writeString(temporaryDirectory.resolve("not-a-directory.txt"), "x");

        IOException error = assertThrows(IOException.class, () -> new ProjectPathGuard(file));
        assertTrue(error.getMessage().startsWith("Le chemin du projet n'est pas un répertoire : "),
                error.getMessage());
    }

    @Test
    void rejectsAMissingRoot() {
        assertThrows(IOException.class, () -> new ProjectPathGuard(temporaryDirectory.resolve("missing")));
    }

    @Test
    void acceptsARootGivenThroughASymbolicLinkAndTrustsItsTarget() throws IOException {
        Path linkedRoot = temporaryDirectory.resolve("linked-root");
        SymbolicLinks.createOrSkip(linkedRoot, project);

        ProjectPathGuard linkedGuard = new ProjectPathGuard(linkedRoot);

        assertEquals(project.toRealPath(), linkedGuard.root());
        assertEquals(app(), linkedGuard.requireRegularFile(linkedGuard.root().resolve("src/main/App.java")));
    }

    // --- resolve(Path) ---

    @Test
    void resolvesANestedRelativePathUnderTheRoot() throws IOException {
        assertEquals(app(), guard.resolve(Path.of("src", "main", "App.java")));
    }

    @Test
    void resolvesANormalisedPathThatStaysInsideTheRoot() throws IOException {
        assertEquals(app(), guard.resolve(Path.of("src", ".", "other", "..", "main", "App.java")));
    }

    @Test
    void resolvesAPathThatDoesNotExistYet() throws IOException {
        assertEquals(guard.root().resolve("new").resolve("File.java"), guard.resolve(Path.of("new", "File.java")));
    }

    @Test
    void rejectsADotDotTraversal() {
        IOException error = assertThrows(IOException.class, () -> guard.resolve(Path.of("..", "outside.txt")));
        assertTrue(error.getMessage().startsWith("Chemin hors repository : "), error.getMessage());
    }

    @Test
    void rejectsATraversalHiddenBehindAnInsideSegment() {
        assertThrows(IOException.class,
                () -> guard.resolve(Path.of("src", "..", "..", "outside", "secret.txt")));
    }

    @Test
    void rejectsAnAbsolutePath() {
        Path absolute = outside.resolve("secret.txt").toAbsolutePath();

        IOException error = assertThrows(IOException.class, () -> guard.resolve(absolute));
        assertTrue(error.getMessage().startsWith("Chemin absolu interdit dans le repository : "),
                error.getMessage());
    }

    @Test
    void rejectsAnAbsolutePathEvenInsideTheRoot() {
        assertThrows(IOException.class, () -> guard.resolve(app()));
    }

    // --- resolve(RepositoryPath) ---

    @Test
    void resolvesASlashSeparatedRepositoryPathOnEveryPlatform() throws IOException {
        assertEquals(app(), guard.resolve(new RepositoryPath("src/main/App.java")));
    }

    @Test
    void rejectsADotDotRepositoryPathBeforeItReachesTheGuard() {
        assertThrows(IllegalArgumentException.class, () -> new RepositoryPath("../outside.txt"));
    }

    @Test
    void handlesABackslashComponentAccordingToTheTargetFileSystem() throws IOException {
        RepositoryPath backslash = new RepositoryPath("src\\App.java");

        if (FileSystems.getDefault().getSeparator().equals("\\")) {
            IOException error = assertThrows(IOException.class, () -> guard.resolve(backslash));
            assertEquals("Invalid repository path", error.getMessage());
            assertTrue(error.getCause() instanceof IllegalArgumentException);
        } else {
            Path resolved = guard.resolve(backslash);
            assertEquals(guard.root().resolve("src\\App.java"), resolved);
            assertEquals(1, guard.root().relativize(resolved).getNameCount());
        }
    }

    // --- requireRegularFile ---

    @Test
    void returnsTheCanonicalRegularFile() throws IOException {
        assertEquals(app(), guard.requireRegularFile(project.resolve("src/main/App.java")));
    }

    @Test
    void rejectsADirectoryWhereARegularFileIsExpected() {
        IOException error = assertThrows(IOException.class,
                () -> guard.requireRegularFile(guard.root().resolve("src").resolve("main")));
        assertEquals("Fichier absent ou non régulier : src/main", error.getMessage());
    }

    @Test
    void rejectsAMissingRegularFile() {
        assertThrows(IOException.class, () -> guard.requireRegularFile(guard.root().resolve("missing.txt")));
    }

    @Test
    void rejectsARegularFileOutsideTheRoot() {
        IOException error = assertThrows(IOException.class,
                () -> guard.requireRegularFile(outside.resolve("secret.txt")));
        assertTrue(error.getMessage().startsWith("Chemin hors repository : "), error.getMessage());
    }

    @Test
    void rejectsARegularFileReachedByLexicalTraversal() {
        assertThrows(IOException.class,
                () -> guard.requireRegularFile(project.resolve("..").resolve("outside").resolve("secret.txt")));
    }

    @Test
    void rejectsAFinalSymbolicLinkToAFileInsideTheProject() {
        Path link = project.resolve("link-to-app.java");
        SymbolicLinks.createOrSkip(link, project.resolve("src/main/App.java"));

        IOException error = assertThrows(IOException.class, () -> guard.requireRegularFile(link));
        assertEquals("Lien symbolique interdit dans le repository : link-to-app.java", error.getMessage());
    }

    @Test
    void rejectsAFinalSymbolicLinkToAFileOutsideTheProject() {
        Path link = project.resolve("src").resolve("main").resolve("leak.txt");
        SymbolicLinks.createOrSkip(link, outside.resolve("secret.txt"));

        IOException error = assertThrows(IOException.class, () -> guard.requireRegularFile(link));
        assertEquals("Lien symbolique interdit dans le repository : src/main/leak.txt", error.getMessage());
    }

    @Test
    void rejectsARegularFileBehindAnIntermediateSymbolicLink() {
        Path linkedDirectory = project.resolve("linked-outside");
        SymbolicLinks.createOrSkip(linkedDirectory, outside);

        IOException error = assertThrows(IOException.class,
                () -> guard.requireRegularFile(linkedDirectory.resolve("secret.txt")));
        assertEquals("Lien symbolique interdit dans le repository : linked-outside", error.getMessage());
    }

    // --- requireDirectory ---

    @Test
    void returnsTheCanonicalDirectory() throws IOException {
        assertEquals(guard.root().resolve("src").resolve("main"), guard.requireDirectory(project.resolve("src/main")));
    }

    @Test
    void acceptsTheRootItselfAsADirectory() throws IOException {
        assertEquals(guard.root(), guard.requireDirectory(project));
    }

    @Test
    void rejectsARegularFileWhereADirectoryIsExpected() {
        IOException error = assertThrows(IOException.class, () -> guard.requireDirectory(app()));
        assertEquals("Répertoire absent ou invalide : src/main/App.java", error.getMessage());
    }

    @Test
    void rejectsAMissingDirectory() {
        assertThrows(IOException.class, () -> guard.requireDirectory(guard.root().resolve("missing")));
    }

    @Test
    void rejectsADirectoryOutsideTheRoot() {
        IOException error = assertThrows(IOException.class, () -> guard.requireDirectory(outside));
        assertTrue(error.getMessage().startsWith("Chemin hors repository : "), error.getMessage());
    }

    @Test
    void rejectsTheParentOfTheRoot() {
        assertThrows(IOException.class, () -> guard.requireDirectory(project.resolve("..")));
    }

    @Test
    void rejectsAFinalSymbolicLinkToADirectory() {
        Path link = project.resolve("linked-src");
        SymbolicLinks.createOrSkip(link, project.resolve("src"));

        IOException error = assertThrows(IOException.class, () -> guard.requireDirectory(link));
        assertEquals("Lien symbolique interdit dans le repository : linked-src", error.getMessage());
    }

    @Test
    void reportsAndRejectsAnIntermediateSymbolicLinkToAnOutsideDirectory() throws IOException {
        Path linkedDirectory = project.resolve("src").resolve("linked-outside");
        SymbolicLinks.createOrSkip(linkedDirectory, outside);
        Path candidate = linkedDirectory.resolve("sub");

        assertTrue(guard.containsSymbolicLink(candidate));
        IOException error = assertThrows(IOException.class, () -> guard.requireDirectory(candidate));
        assertEquals("Lien symbolique interdit dans le repository : src/linked-outside", error.getMessage());
    }

    // --- containsSymbolicLink ---

    @Test
    void reportsNoSymbolicLinkOnAPlainPath() throws IOException {
        assertFalse(guard.containsSymbolicLink(app()));
        assertFalse(guard.containsSymbolicLink(guard.root().resolve("missing").resolve("child")));
    }

    @Test
    void reportsNoSymbolicLinkOnTheRoot() throws IOException {
        assertFalse(guard.containsSymbolicLink(project));
    }

    @Test
    void reportsAFinalSymbolicLink() throws IOException {
        Path link = project.resolve("link-to-app.java");
        SymbolicLinks.createOrSkip(link, project.resolve("src/main/App.java"));

        assertTrue(guard.containsSymbolicLink(link));
    }

    @Test
    void reportsAnIntermediateSymbolicLinkToAFileOutsideTheProject() throws IOException {
        Path linkedDirectory = project.resolve("linked-outside");
        SymbolicLinks.createOrSkip(linkedDirectory, outside);

        assertTrue(guard.containsSymbolicLink(linkedDirectory.resolve("secret.txt")));
    }

    @Test
    void rejectsALinkQueryOutsideTheRoot() {
        assertThrows(IOException.class, () -> guard.containsSymbolicLink(outside.resolve("secret.txt")));
        assertThrows(IOException.class, () -> guard.containsSymbolicLink(project.resolve("..")));
    }

    // --- repositoryPath ---

    @Test
    void encodesANestedRepositoryPathWithForwardSlashes() {
        assertEquals("src/main/App.java", guard.repositoryPath(app()));
    }

    @Test
    void normalisesTheCandidateBeforeEncoding() {
        Path candidate = guard.root().resolve("src").resolve("other").resolve("..").resolve("main").resolve("App.java");

        assertEquals("src/main/App.java", guard.repositoryPath(candidate));
    }

    @Test
    void encodesTheRootAsTheEmptyRepositoryPath() {
        assertEquals("", guard.repositoryPath(guard.root()));
    }

    @Test
    void masksAPathOutsideTheRoot() throws IOException {
        assertEquals("[INTERNAL_PATH]", guard.repositoryPath(outside.toRealPath().resolve("secret.txt")));
        assertEquals("[INTERNAL_PATH]", guard.repositoryPath(guard.root().resolve("..").resolve("outside")));
    }
}
