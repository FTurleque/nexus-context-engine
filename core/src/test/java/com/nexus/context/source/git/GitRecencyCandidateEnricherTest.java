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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitRecencyCandidateEnricherTest {

    @TempDir
    Path temporaryDirectory;

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
