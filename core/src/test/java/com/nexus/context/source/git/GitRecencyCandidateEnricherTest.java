package com.nexus.context.source.git;

import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import com.nexus.search.CandidateType;
import com.nexus.search.SearchCandidate;
import com.nexus.search.SearchSignals;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitRecencyCandidateEnricherTest {

    @TempDir
    Path temporaryDirectory;


    @Test
    void recencyKeepsPosixBackslashAndSlashCandidatesSeparate() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(temporaryDirectory.getFileSystem().getSeparator().equals("/"));
        Path backslash = write(temporaryDirectory, "a\\b.java", "class Backslash {}\n");
        Path slash = write(temporaryDirectory, "a/b.java", "class Slash {}\n");
        try (Git git = Git.init().setDirectory(temporaryDirectory.toFile()).call()) {
            commitAll(git, "initial");
            Files.writeString(backslash, "class Backslash { void changed() {} }\n");
            commitAll(git, "backslash only");
        }
        var enriched = new GitRecencyCandidateEnricher().enrich(project(temporaryDirectory),
                List.of(candidate("backslash", backslash), candidate("slash", slash)));
        assertEquals(1.0, enriched.getFirst().signals().get(SearchSignals.GIT_RECENCY));
        assertFalse(enriched.getLast().signals().containsKey(SearchSignals.GIT_RECENCY));
    }

    @Test
    void addsRecencyOnlyToCandidatesTouchedByRecentCommits() throws Exception {
        Path oldFile = write(temporaryDirectory, "src/OldService.java", "class OldService {}\n");
        Path recentFile = write(temporaryDirectory, "src/RecentService.java", "class RecentService {}\n");

        try (Git git = Git.init().setDirectory(temporaryDirectory.toFile()).call()) {
            commitAll(git, "initial");
            Files.writeString(recentFile, "class RecentService { void changed() {} }\n");
            commitAll(git, "change recent service");
        }

        ProjectDescriptor project = project(temporaryDirectory);
        List<SearchCandidate> candidates = List.of(
                candidate("old", oldFile),
                candidate("recent", recentFile));

        List<SearchCandidate> enriched = new GitRecencyCandidateEnricher().enrich(project, candidates);

        SearchCandidate oldCandidate = enriched.stream()
                .filter(candidate -> candidate.id().equals("old"))
                .findFirst()
                .orElseThrow();
        SearchCandidate recentCandidate = enriched.stream()
                .filter(candidate -> candidate.id().equals("recent"))
                .findFirst()
                .orElseThrow();

        assertFalse(oldCandidate.signals().containsKey(SearchSignals.GIT_RECENCY));
        assertEquals(1.0d, recentCandidate.signals().get(SearchSignals.GIT_RECENCY), 0.000001d);
    }

    @Test
    void resolvesRecencyForANestedProjectInAMonorepo() throws Exception {
        Path projectRoot = temporaryDirectory.resolve("backend/order-app");
        Path target = write(projectRoot, "src/OrderService.java", "class OrderService {}\n");
        write(temporaryDirectory, "frontend/App.ts", "export const app = true;\n");

        try (Git git = Git.init().setDirectory(temporaryDirectory.toFile()).call()) {
            commitAll(git, "initial monorepo");
            Files.writeString(target, "class OrderService { void changed() {} }\n");
            commitAll(git, "change nested order service");
        }

        SearchCandidate candidate = candidate("order", target);
        List<SearchCandidate> enriched = new GitRecencyCandidateEnricher().enrich(
                project(projectRoot),
                List.of(candidate));

        assertEquals(1.0d, enriched.getFirst().signals().get(SearchSignals.GIT_RECENCY), 0.000001d);
    }

    @Test
    void leavesCandidatesUntouchedOutsideAGitRepository() throws Exception {
        Path file = write(temporaryDirectory, "src/Service.java", "class Service {}\n");
        SearchCandidate candidate = candidate("service", file);

        List<SearchCandidate> enriched = new GitRecencyCandidateEnricher().enrich(
                project(temporaryDirectory),
                List.of(candidate));

        assertEquals(1, enriched.size());
        assertTrue(enriched.getFirst().signals().containsKey(SearchSignals.LEXICAL));
        assertFalse(enriched.getFirst().signals().containsKey(SearchSignals.GIT_RECENCY));
    }

    @Test
    void outsideAGitRepositoryNoWarningAndNoExplanationReasonAreEmitted() throws Exception {
        Path file = write(temporaryDirectory, "src/Service.java", "class Service {}\n");
        List<SearchCandidate> candidates = List.of(candidate("service", file));
        List<String> reasons = new ArrayList<>();

        List<LogRecord> records = captureWarnings(() -> new GitRecencyCandidateEnricher().enrich(
                project(temporaryDirectory), candidates, reasons::add));

        assertEquals(List.of(), records);
        assertEquals(List.of(), reasons);
    }

    @Test
    void jgitIncidentKeepsCandidatesLogsOneWarningWithoutPathAndExplainsTheMissingSignal() throws Exception {
        Path file = write(temporaryDirectory, "src/Service.java", "class Service {}\n");
        String branch;
        try (Git git = Git.init().setDirectory(temporaryDirectory.toFile()).call()) {
            commitAll(git, "initial");
            Files.writeString(file, "class Service { void changed() {} }\n");
            commitAll(git, "change service");
            branch = git.getRepository().getFullBranch();
        }
        // La branche courante pointe vers un commit absent : le parcours de l'historique échoue.
        Files.writeString(temporaryDirectory.resolve(".git").resolve(branch),
                "0123456789abcdef0123456789abcdef01234567\n");
        List<SearchCandidate> candidates = List.of(candidate("service", file));
        List<String> reasons = new ArrayList<>();
        List<List<SearchCandidate>> results = new ArrayList<>();

        List<LogRecord> records = captureWarnings(() -> results.add(new GitRecencyCandidateEnricher().enrich(
                project(temporaryDirectory), candidates, reasons::add)));

        assertEquals(candidates, results.getFirst());
        assertEquals(1, records.size());
        LogRecord warning = records.getFirst();
        assertEquals(Level.WARNING, warning.getLevel());
        String message = java.text.MessageFormat.format(warning.getMessage(),
                warning.getParameters() == null ? new Object[0] : warning.getParameters());
        assertTrue(message.contains("org.eclipse.jgit"), message);
        for (String forbidden : List.of(
                temporaryDirectory.toString(),
                temporaryDirectory.toAbsolutePath().toString().replace('\\', '/'),
                temporaryDirectory.toRealPath().toString(),
                "class Service")) {
            assertFalse(message.contains(forbidden), message);
        }
        assertNull(warning.getThrown());
        assertEquals(List.of(GitRecencyCandidateEnricher.UNAVAILABLE_REASON), reasons);
        assertEquals("récence Git indisponible", GitRecencyCandidateEnricher.UNAVAILABLE_REASON);
    }

    @Test
    void failsOpenWhenCandidatePathBudgetIsExceeded() throws Exception {
        write(temporaryDirectory, "src/Service.java", "class Service {}\n");
        try (Git git = Git.init().setDirectory(temporaryDirectory.toFile()).call()) {
            commitAll(git, "initial");
        }

        List<SearchCandidate> candidates = new ArrayList<>();
        for (int index = 0; index <= GitRecencyCandidateEnricher.MAX_CANDIDATE_PATHS; index++) {
            candidates.add(candidate(
                    "candidate-" + index,
                    temporaryDirectory.resolve("generated/Candidate" + index + ".java")));
        }

        List<String> reasons = new ArrayList<>();
        List<SearchCandidate> enriched = new GitRecencyCandidateEnricher().enrich(
                project(temporaryDirectory),
                candidates,
                reasons::add);

        assertEquals(candidates, enriched);
        assertEquals(List.of(GitRecencyCandidateEnricher.BUDGET_REASON), reasons);
        assertTrue(enriched.stream().noneMatch(candidate ->
                candidate.signals().containsKey(SearchSignals.GIT_RECENCY)));
    }

    private interface Action {
        void run() throws Exception;
    }

    /** Enregistrements {@code WARNING} ou plus émis par l'enrichisseur pendant {@code action}. */
    private static List<LogRecord> captureWarnings(Action action) throws Exception {
        Logger logger = Logger.getLogger(GitRecencyCandidateEnricher.class.getName());
        List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                if (logRecord.getLevel().intValue() >= Level.WARNING.intValue()) {
                    records.add(logRecord);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
        }
        return List.copyOf(records);
    }

    private static Path write(Path root, String relativePath, String content) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private static void commitAll(Git git, String message) throws Exception {
        git.add().addFilepattern(".").call();
        git.commit()
                .setMessage(message)
                .setAuthor("NEXUS Test", "nexus@example.test")
                .setCommitter("NEXUS Test", "nexus@example.test")
                .call();
    }

    private static ProjectDescriptor project(Path root) {
        return new ProjectDescriptor(
                UUID.randomUUID(),
                "git-test",
                root,
                ProjectSourceType.LOCAL,
                Set.of("java"),
                Set.of(),
                null,
                IndexStatus.READY);
    }

    private static SearchCandidate candidate(String id, Path path) {
        return new SearchCandidate(
                id,
                CandidateType.FILE,
                path,
                null,
                path.getFileName().toString(),
                Map.of(SearchSignals.LEXICAL, 0.5d));
    }
}
