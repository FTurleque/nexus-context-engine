package com.nexus.context.source.git;

import com.nexus.context.source.git.GitRepositoryLocator.Ownership;
import com.nexus.context.source.git.GitRepositoryLocator.RejectedParentRepositoryException;
import com.nexus.security.PublicDiagnosticPolicy;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Scénarios de {@code git-context-discovery} (BE-0067) : plafond de la découverte à la racine du
 * projet et contrôle du propriétaire d'un dépôt parent, simulé par injection.
 */
class GitRepositoryLocatorTest {

    private static final Pattern ABSOLUTE_PATH = Pattern.compile("(?:[A-Za-z]:[/\\\\]|\\\\\\\\|(?:^|\\s)/)");

    @TempDir
    Path temporaryDirectory;

    @Test
    void retainsTheProjectRootRepositoryWithoutOwnershipCheck() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        Git.init().setDirectory(projectRoot.toFile()).call().close();
        GitRepositoryLocator locator = new GitRepositoryLocator(entry -> fail("propriétaire consulté : " + entry));

        try (Repository repository = locator.open(projectRoot)) {
            assertEquals(projectRoot.resolve(".git").toRealPath(), repository.getDirectory().toPath().toRealPath());
        }
    }

    @Test
    void retainsAParentRepositoryOwnedByTheCurrentUser() throws Exception {
        Git.init().setDirectory(temporaryDirectory.toFile()).call().close();
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("backend/order-app"));
        List<Path> inspected = new ArrayList<>();
        GitRepositoryLocator locator = new GitRepositoryLocator(entry -> {
            inspected.add(entry);
            return Ownership.CURRENT_USER;
        });

        try (Repository repository = locator.open(projectRoot)) {
            assertEquals(temporaryDirectory.toRealPath(), repository.getWorkTree().toPath().toRealPath());
        }
        assertEquals(List.of(temporaryDirectory.toRealPath().resolve(".git")), inspected);
    }

    @Test
    void rejectsAParentRepositoryOfAnotherAccountBeforeReadingItsConfiguration() throws Exception {
        Git.init().setDirectory(temporaryDirectory.toFile()).call().close();
        // Une configuration illisible ferait échouer toute ouverture qui la lirait.
        Files.writeString(temporaryDirectory.resolve(".git/config"), "[core\n\tinvalid = = =\n");
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        GitRepositoryLocator locator = new GitRepositoryLocator(entry -> Ownership.OTHER_ACCOUNT);

        RejectedParentRepositoryException rejected =
                assertThrows(RejectedParentRepositoryException.class, () -> locator.open(projectRoot));

        assertEquals(GitRepositoryLocator.PARENT_REPOSITORY_REJECTED, rejected.getMessage());
    }

    @Test
    void rejectsAParentRepositoryWhoseOwnerCannotBeDetermined() throws Exception {
        Git.init().setDirectory(temporaryDirectory.toFile()).call().close();
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        GitRepositoryLocator locator = new GitRepositoryLocator(entry -> Ownership.UNDETERMINED);

        assertThrows(RejectedParentRepositoryException.class, () -> locator.open(projectRoot));
    }

    @Test
    void checksBothTheGitLinkAndItsTargetForAParentRepository() throws Exception {
        Path separateGitDir = temporaryDirectory.resolve("storage/monorepo.git");
        Path workTree = Files.createDirectories(temporaryDirectory.resolve("monorepo"));
        Git.init().setDirectory(workTree.toFile()).setGitDir(separateGitDir.toFile()).call().close();
        assertTrue(Files.isRegularFile(workTree.resolve(".git")));
        Path projectRoot = Files.createDirectories(workTree.resolve("service"));
        List<Path> inspected = new ArrayList<>();
        GitRepositoryLocator locator = new GitRepositoryLocator(entry -> {
            inspected.add(entry);
            return inspected.size() == 1 ? Ownership.CURRENT_USER : Ownership.OTHER_ACCOUNT;
        });

        assertThrows(RejectedParentRepositoryException.class, () -> locator.open(projectRoot));

        assertEquals(2, inspected.size());
        assertEquals(workTree.toRealPath().resolve(".git"), inspected.get(0));
        assertEquals(separateGitDir.toRealPath(), inspected.get(1).toRealPath());
    }

    @Test
    void stopsAtTheFirstGitEntryInsteadOfSearchingHigher() throws Exception {
        Git.init().setDirectory(temporaryDirectory.toFile()).call().close();
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        Files.createDirectories(projectRoot.resolve(".git"));
        GitRepositoryLocator locator = new GitRepositoryLocator(entry -> Ownership.CURRENT_USER);

        assertThrows(RepositoryNotFoundException.class, () -> locator.open(projectRoot));
    }

    @Test
    void theRejectionDiagnosticIsPublicStableAndHasNoAbsolutePathNorAccountName() {
        String diagnostic = GitRepositoryLocator.PARENT_REPOSITORY_REJECTED;

        assertEquals(diagnostic, new PublicDiagnosticPolicy(List.of(temporaryDirectory)).text(diagnostic));
        assertFalse(ABSOLUTE_PATH.matcher(diagnostic).find(), diagnostic);
        String account = System.getProperty("user.name");
        if (account != null && !account.isBlank()) {
            assertFalse(diagnostic.contains(account), diagnostic);
        }
    }

    @Test
    void theFileSystemResolverRecognisesADirectoryCreatedByTheCurrentUser() throws Exception {
        Path created = Files.createDirectories(temporaryDirectory.resolve("owned/.git"));

        Ownership ownership = new GitRepositoryLocator.FileOwnershipResolver().ownership(created);

        // Windows (NTFS) et Linux (ext4, tmpfs) déclarent un propriétaire : l'utilisateur courant.
        assertEquals(Ownership.CURRENT_USER, ownership);
    }

    @Test
    void theFileSystemResolverReportsAMissingEntryAsUndetermined() {
        Ownership ownership = new GitRepositoryLocator.FileOwnershipResolver()
                .ownership(temporaryDirectory.resolve("missing/.git"));

        assertEquals(Ownership.UNDETERMINED, ownership);
    }
}
