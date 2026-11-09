package com.nexus.context.source.git;

import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import com.nexus.search.candidate.CandidateType;
import com.nexus.search.candidate.SearchCandidate;
import com.nexus.search.candidate.SearchSignals;
import com.nexus.testsupport.SymbolicLinks;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
    void resolvesRecencyForANestedProjectRegisteredThroughALinkedRoot() throws Exception {
        Path monorepo = Files.createDirectories(temporaryDirectory.resolve("monorepo"));
        Path realTarget = write(monorepo, "backend/order-app/src/OrderService.java", "class OrderService {}\n");
        try (Git git = Git.init().setDirectory(monorepo.toFile()).call()) {
            commitAll(git, "initial monorepo");
            Files.writeString(realTarget, "class OrderService { void changed() {} }\n");
            commitAll(git, "change nested order service");
        }
        Path linkedMonorepo = temporaryDirectory.resolve("linked-monorepo");
        SymbolicLinks.createOrSkip(linkedMonorepo, monorepo);
        Path projectRoot = linkedMonorepo.resolve("backend/order-app");

        SearchCandidate candidate = candidate("order", projectRoot.resolve("src/OrderService.java"));
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
    void aParentRepositoryOfAnotherAccountGivesNoRecencyAndExplainsWhyWithoutWarning() throws Exception {
        Path projectRoot = temporaryDirectory.resolve("nested");
        Path file = write(projectRoot, "src/Service.java", "class Service {}\n");
        try (Git git = Git.init().setDirectory(temporaryDirectory.toFile()).call()) {
            commitAll(git, "initial");
            Files.writeString(file, "class Service { void changed() {} }\n");
            commitAll(git, "change service");
        }
        List<SearchCandidate> candidates = List.of(candidate("service", file));
        List<String> reasons = new ArrayList<>();
        List<List<SearchCandidate>> results = new ArrayList<>();
        GitRecencyCandidateEnricher enricher = new GitRecencyCandidateEnricher(
                GitRecencyCandidateEnricher.DEFAULT_RECENCY_CACHE_CAPACITY,
                new GitRepositoryLocator(entry -> GitRepositoryLocator.Ownership.OTHER_ACCOUNT));

        List<LogRecord> records = captureWarnings(() -> results.add(
                enricher.enrich(project(projectRoot), candidates, reasons::add)));

        assertEquals(candidates, results.getFirst());
        assertEquals(List.of(), records);
        assertEquals(List.of(GitRecencyCandidateEnricher.REJECTED_REPOSITORY_REASON), reasons);
        assertEquals(0, enricher.logScans());
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
    void computesRecencyWithoutBudgetReasonAtExactlyTheCandidatePathBudget() throws Exception {
        Path recent = write(temporaryDirectory, "src/Service.java", "class Service {}\n");
        try (Git git = Git.init().setDirectory(temporaryDirectory.toFile()).call()) {
            commitAll(git, "initial");
            Files.writeString(recent, "class Service { void changed() {} }\n");
            commitAll(git, "change service");
        }
        List<SearchCandidate> candidates = new ArrayList<>();
        candidates.add(candidate("service", recent));
        for (int index = 1; index < GitRecencyCandidateEnricher.MAX_CANDIDATE_PATHS; index++) {
            candidates.add(candidate(
                    "candidate-" + index,
                    temporaryDirectory.resolve("generated/Candidate" + index + ".java")));
        }
        List<String> reasons = new ArrayList<>();
        List<List<SearchCandidate>> results = new ArrayList<>();

        List<LogRecord> records = captureWarnings(() -> results.add(new GitRecencyCandidateEnricher().enrich(
                project(temporaryDirectory),
                candidates,
                reasons::add)));

        List<SearchCandidate> enriched = results.getFirst();
        assertEquals(GitRecencyCandidateEnricher.MAX_CANDIDATE_PATHS, enriched.size());
        assertEquals(List.of(), reasons);
        assertEquals(List.of(), records);
        assertEquals(1.0d, enriched.getFirst().signals().get(SearchSignals.GIT_RECENCY), 0.000001d);
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
        List<List<SearchCandidate>> results = new ArrayList<>();
        GitRecencyCandidateEnricher enricher = new GitRecencyCandidateEnricher();

        List<LogRecord> records = captureWarnings(() -> results.add(enricher.enrich(
                project(temporaryDirectory),
                candidates,
                reasons::add)));

        List<SearchCandidate> enriched = results.getFirst();
        assertEquals(candidates, enriched);
        assertEquals(List.of(GitRecencyCandidateEnricher.BUDGET_REASON), reasons);
        assertEquals(List.of(), records);
        assertTrue(enriched.stream().noneMatch(candidate ->
                candidate.signals().containsKey(SearchSignals.GIT_RECENCY)));
        // Le budget de candidats est contrôlé avant toute lecture de l'historique.
        assertEquals(0, enricher.logScans());
    }

    @Test
    void computesRecencyFromTheCacheAtExactlyTheChangedPathsPerCommitBudget() throws Exception {
        Path target = temporaryDirectory.resolve("src/Target.java");
        // Target modifié + 1 023 ajouts = exactement 1 024 chemins modifiés par ce commit.
        bulkHistory(GitRecencyCandidateEnricher.MAX_CHANGED_PATHS_PER_COMMIT - 1);
        List<SearchCandidate> candidates = List.of(candidate("target", target));
        List<String> reasons = new ArrayList<>();
        List<List<SearchCandidate>> results = new ArrayList<>();
        GitRecencyCandidateEnricher enricher = new GitRecencyCandidateEnricher();

        List<LogRecord> records = captureWarnings(() -> results.add(enricher.enrich(
                project(temporaryDirectory), candidates, reasons::add)));

        assertEquals(1.0d, results.getFirst().getFirst().signals().get(SearchSignals.GIT_RECENCY), 0.000001d);
        assertEquals(List.of(), reasons);
        assertEquals(List.of(), records);
        // Historique dans les bornes : un seul parcours, celui qui alimente le cache.
        assertEquals(1, enricher.logScans());
    }

    /**
     * Comportement réel au-delà de la borne par commit : l'index non filtré n'est pas mémorisable,
     * puis le parcours filtré par les chemins candidats calcule la récence. Aucune raison de budget
     * n'est ajoutée, contrairement au scénario « Commit au-delà de 1 024 chemins » du delta
     * {@code preciser-bornes-redaction-recence-git}.
     */
    @Test
    void aCommitBeyondTheChangedPathsPerCommitBudgetFallsBackToTheFilteredWalkWithoutBudgetReason()
            throws Exception {
        Path target = temporaryDirectory.resolve("src/Target.java");
        // Target modifié + 1 024 ajouts = 1 025 chemins modifiés par ce commit.
        bulkHistory(GitRecencyCandidateEnricher.MAX_CHANGED_PATHS_PER_COMMIT);
        List<SearchCandidate> candidates = List.of(candidate("target", target));
        List<String> reasons = new ArrayList<>();
        List<List<SearchCandidate>> results = new ArrayList<>();
        GitRecencyCandidateEnricher enricher = new GitRecencyCandidateEnricher();

        List<LogRecord> records = captureWarnings(() -> results.add(enricher.enrich(
                project(temporaryDirectory), candidates, reasons::add)));

        assertEquals(1.0d, results.getFirst().getFirst().signals().get(SearchSignals.GIT_RECENCY), 0.000001d);
        assertEquals(List.of(), reasons);
        assertEquals(List.of(), records);
        // Parcours non filtré interrompu à la borne, puis parcours filtré.
        assertEquals(2, enricher.logScans());
    }

    @Test
    void computesRecencyAtExactlyTheCumulativeChangedPathsBudget() throws Exception {
        int fullRewrites = GitRecencyCandidateEnricher.MAX_CUMULATIVE_CHANGED_PATHS
                / GitRecencyCandidateEnricher.MAX_CANDIDATE_PATHS;
        List<SearchCandidate> candidates = candidateHistory(fullRewrites, false);
        List<String> reasons = new ArrayList<>();
        List<List<SearchCandidate>> results = new ArrayList<>();
        GitRecencyCandidateEnricher enricher = new GitRecencyCandidateEnricher();

        List<LogRecord> records = captureWarnings(() -> results.add(enricher.enrich(
                project(temporaryDirectory), candidates, reasons::add)));

        List<SearchCandidate> enriched = results.getFirst();
        assertEquals(candidates.size(), enriched.size());
        assertTrue(enriched.stream().allMatch(candidate ->
                candidate.signals().get(SearchSignals.GIT_RECENCY) == 1.0d));
        assertEquals(List.of(), reasons);
        assertEquals(List.of(), records);
        assertEquals(1, enricher.logScans());
    }

    @Test
    void failsOpenWhenCumulativeChangedPathsBudgetIsExceeded() throws Exception {
        int fullRewrites = GitRecencyCandidateEnricher.MAX_CUMULATIVE_CHANGED_PATHS
                / GitRecencyCandidateEnricher.MAX_CANDIDATE_PATHS;
        // Chaque commit reste sous la borne par commit ; le dernier porte le cumul à 4 097.
        List<SearchCandidate> candidates = candidateHistory(fullRewrites, true);
        List<String> reasons = new ArrayList<>();
        List<List<SearchCandidate>> results = new ArrayList<>();
        GitRecencyCandidateEnricher enricher = new GitRecencyCandidateEnricher();

        List<LogRecord> records = captureWarnings(() -> results.add(enricher.enrich(
                project(temporaryDirectory), candidates, reasons::add)));

        List<SearchCandidate> enriched = results.getFirst();
        assertEquals(candidates, enriched);
        assertTrue(enriched.stream().noneMatch(candidate ->
                candidate.signals().containsKey(SearchSignals.GIT_RECENCY)));
        assertEquals(List.of(GitRecencyCandidateEnricher.BUDGET_REASON), reasons);
        assertEquals("récence Git ignorée : budget de chemins dépassé", GitRecencyCandidateEnricher.BUDGET_REASON);
        assertEquals(List.of(), records);
        // Parcours non filtré puis parcours filtré, tous deux interrompus par la borne cumulée.
        assertEquals(2, enricher.logScans());
    }

    /**
     * Dépôt de {@value GitRecencyCandidateEnricher#MAX_CANDIDATE_PATHS} fichiers candidats : un
     * commit initial (sans parent, non compté), puis {@code fullRewrites} commits qui modifient tous
     * les candidats, puis éventuellement un commit qui ne modifie que le premier candidat.
     */
    private List<SearchCandidate> candidateHistory(int fullRewrites, boolean extraSingleChange) throws Exception {
        int count = GitRecencyCandidateEnricher.MAX_CANDIDATE_PATHS;
        // Les commits sont écrits directement dans la base d'objets : la récence ne lit que
        // l'historique, et « git add » de 512 fichiers par commit serait inutilement lent.
        Map<String, String> contents = new TreeMap<>();
        for (int index = 0; index < count; index++) {
            contents.put("C" + index + ".java", "class C" + index + " {}\n");
        }
        try (Git git = Git.init().setDirectory(temporaryDirectory.toFile()).call()) {
            Repository repository = git.getRepository();
            ObjectId head = commitTree(repository, null, contents, "initial");
            for (int round = 1; round <= fullRewrites; round++) {
                for (int index = 0; index < count; index++) {
                    contents.put("C" + index + ".java", "class C" + index + " { int v = " + round + "; }\n");
                }
                head = commitTree(repository, head, contents, "rewrite " + round);
            }
            if (extraSingleChange) {
                contents.put("C0.java", "class C0 { int extra; }\n");
                commitTree(repository, head, contents, "single change");
            }
        }
        List<SearchCandidate> candidates = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            candidates.add(candidate("c-" + index, temporaryDirectory.resolve("src/C" + index + ".java")));
        }
        return List.copyOf(candidates);
    }

    /** Écrit un commit dont l'arbre contient {@code src/<nom>} pour chaque entrée, puis y avance HEAD. */
    private static ObjectId commitTree(
            Repository repository,
            ObjectId parent,
            Map<String, String> sortedContents,
            String message) throws Exception {
        ObjectId commitId;
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            TreeFormatter src = new TreeFormatter();
            for (Map.Entry<String, String> entry : sortedContents.entrySet()) {
                src.append(entry.getKey(), FileMode.REGULAR_FILE,
                        inserter.insert(Constants.OBJ_BLOB, entry.getValue().getBytes(StandardCharsets.UTF_8)));
            }
            TreeFormatter root = new TreeFormatter();
            root.append("src", FileMode.TREE, inserter.insert(src));
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(inserter.insert(root));
            if (parent != null) {
                commit.setParentId(parent);
            }
            PersonIdent ident = new PersonIdent("NEXUS Test", "nexus@example.test");
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage(message);
            commitId = inserter.insert(commit);
            inserter.flush();
        }
        RefUpdate update = repository.updateRef(Constants.HEAD);
        update.setNewObjectId(commitId);
        update.setForceUpdate(true);
        RefUpdate.Result result = update.update();
        assertTrue(result == RefUpdate.Result.NEW || result == RefUpdate.Result.FORCED, result.name());
        return commitId;
    }

    /**
     * Dépôt à deux commits : {@code src/Target.java} seul, puis Target modifié avec
     * {@code addedFiles} fichiers ajoutés, soit {@code addedFiles + 1} chemins modifiés.
     */
    private void bulkHistory(int addedFiles) throws Exception {
        Map<String, String> contents = new TreeMap<>();
        contents.put("Target.java", "class Target {}\n");
        try (Git git = Git.init().setDirectory(temporaryDirectory.toFile()).call()) {
            Repository repository = git.getRepository();
            ObjectId initial = commitTree(repository, null, contents, "initial");
            contents.put("Target.java", "class Target { void changed() {} }\n");
            for (int index = 0; index < addedFiles; index++) {
                contents.put("Bulk" + index + ".txt", "bulk " + index + "\n");
            }
            commitTree(repository, initial, contents, "change target and add bulk files");
        }
    }

    @Test
    void reusesRecentChangedPathsWhileHeadIsUnchangedAndRecomputesAfterANewCommit() throws Exception {
        Path service = write(temporaryDirectory, "src/Service.java", "class Service {}\n");
        Path added = temporaryDirectory.resolve("src/Nouveau.java");
        try (Git git = Git.init().setDirectory(temporaryDirectory.toFile()).call()) {
            commitAll(git, "initial");
            Files.writeString(service, "class Service { void changed() {} }\n");
            commitAll(git, "change service");

            GitRecencyCandidateEnricher enricher = new GitRecencyCandidateEnricher();
            ProjectDescriptor project = project(temporaryDirectory);
            List<SearchCandidate> candidates = List.of(candidate("service", service), candidate("nouveau", added));

            List<SearchCandidate> first = enricher.enrich(project, candidates);
            assertEquals(1, enricher.logScans());
            List<SearchCandidate> second = enricher.enrich(project, candidates);
            // HEAD inchangé : le journal des commits n'est pas relu et le résultat est identique.
            assertEquals(1, enricher.logScans());
            assertEquals(first, second);
            assertEquals(1.0d, second.getFirst().signals().get(SearchSignals.GIT_RECENCY), 0.000001d);
            assertFalse(second.getLast().signals().containsKey(SearchSignals.GIT_RECENCY));

            // Nouveau commit : l'entrée de l'ancien HEAD est invalidée et le nouveau fichier est pris en compte.
            write(temporaryDirectory, "src/Nouveau.java", "class Nouveau {}\n");
            commitAll(git, "add nouveau");
            List<SearchCandidate> third = enricher.enrich(project, candidates);
            assertEquals(2, enricher.logScans());
            assertEquals(1.0d, third.getLast().signals().get(SearchSignals.GIT_RECENCY), 0.000001d);
            assertEquals(Math.max(0.05d, 1.0d - 1.0d / (GitRecencyCandidateEnricher.MAX_COMMITS - 1)),
                    third.getFirst().signals().get(SearchSignals.GIT_RECENCY), 0.000001d);
        }
    }

    @Test
    void keepsTheFilteredWalkWhenTheHistoryExceedsTheCacheBound() throws Exception {
        Path target = write(temporaryDirectory, "src/Target.java", "class Target {}\n");
        try (Git git = Git.init().setDirectory(temporaryDirectory.toFile()).call()) {
            commitAll(git, "initial");
            Files.writeString(target, "class Target { void changed() {} }\n");
            commitAll(git, "change target");
            // Un commit qui ajoute plus de chemins que la borne par commit.
            for (int index = 0; index <= GitRecencyCandidateEnricher.MAX_CHANGED_PATHS_PER_COMMIT; index++) {
                write(temporaryDirectory, "bulk/File" + index + ".txt", "y" + index);
            }
            commitAll(git, "add every bulk file");
        }
        GitRecencyCandidateEnricher enricher = new GitRecencyCandidateEnricher();
        List<SearchCandidate> candidates = List.of(candidate("target", target));

        List<SearchCandidate> first = enricher.enrich(project(temporaryDirectory), candidates);
        List<SearchCandidate> second = enricher.enrich(project(temporaryDirectory), candidates);

        // Résultat identique au parcours filtré historique : seul le chemin candidat compte.
        assertEquals(first, second);
        assertTrue(first.getFirst().signals().containsKey(SearchSignals.GIT_RECENCY));
        // Le marqueur « trop large » est mémorisé : le parcours non filtré n'est pas refait.
        assertEquals(3, enricher.logScans());
    }

    @Test
    void evictsTheOldestRepositoryWhenTheRecencyCacheIsFull() throws Exception {
        Path firstRoot = temporaryDirectory.resolve("first");
        Path secondRoot = temporaryDirectory.resolve("second");
        Path firstFile = write(firstRoot, "A.java", "class A {}\n");
        Path secondFile = write(secondRoot, "B.java", "class B {}\n");
        for (Path root : List.of(firstRoot, secondRoot)) {
            try (Git git = Git.init().setDirectory(root.toFile()).call()) {
                commitAll(git, "initial");
            }
        }
        GitRecencyCandidateEnricher enricher = new GitRecencyCandidateEnricher(1);

        enricher.enrich(project(firstRoot), List.of(candidate("a", firstFile)));
        enricher.enrich(project(secondRoot), List.of(candidate("b", secondFile)));
        enricher.enrich(project(firstRoot), List.of(candidate("a", firstFile)));

        // Capacité 1 : le premier dépôt a été évincé par le second puis relu.
        assertEquals(3, enricher.logScans());
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
                // Les enregistrements sont gardés en mémoire : rien à vider.
            }

            @Override
            public void close() {
                // Aucune ressource à libérer : le handler est retiré après l'action.
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
