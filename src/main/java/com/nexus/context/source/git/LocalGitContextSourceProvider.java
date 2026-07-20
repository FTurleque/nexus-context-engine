package com.nexus.context.source.git;

import com.nexus.context.ContextFragment;
import com.nexus.search.CandidateType;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provider Git local, borné et strictement en lecture seule.
 */
public final class LocalGitContextSourceProvider implements GitContextSourceProvider {

    static final int MAX_COMMITS = 50;
    static final int MAX_TARGET_HISTORY_PATHS = 5;
    static final int MAX_HISTORY_PER_PATH = 5;
    static final int MAX_CO_CHANGES = 8;

    @Override
    public String id() {
        return "local-git";
    }

    @Override
    public GitContextResult discover(GitContextQuery query) throws IOException {
        Set<String> targets = normalizedTargets(query.targetPaths());
        if (targets.isEmpty()) {
            return new GitContextResult(List.of(), true, true, 0, 0, 0, List.of());
        }

        List<String> diagnostics = new ArrayList<>();
        List<CommitSummary> related = new ArrayList<>();
        Map<String, List<CommitSummary>> history = new LinkedHashMap<>();
        Map<String, Integer> coChanges = new HashMap<>();
        int commitsInspected = 0;

        try (Repository repository = openRepository(query.project().rootPath());
             Git git = new Git(repository);
             RevWalk revWalk = new RevWalk(repository);
             DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            diffFormatter.setRepository(repository);
            diffFormatter.setDetectRenames(true);

            for (RevCommit commit : git.log().setMaxCount(MAX_COMMITS).call()) {
                commitsInspected++;
                if (commit.getParentCount() == 0) {
                    continue;
                }
                RevCommit parent = revWalk.parseCommit(commit.getParent(0).getId());
                Set<String> changed = changedPaths(diffFormatter, parent, commit);
                Set<String> touchedTargets = intersection(changed, targets);
                if (touchedTargets.isEmpty()) {
                    continue;
                }

                CommitSummary summary = new CommitSummary(
                        commit.getId().abbreviate(8).name(),
                        Instant.ofEpochSecond(commit.getCommitTime()),
                        commit.getShortMessage(),
                        List.copyOf(changed.stream().sorted().toList()));
                related.add(summary);

                for (String target : touchedTargets) {
                    List<CommitSummary> fileHistory = history.computeIfAbsent(target, ignored -> new ArrayList<>());
                    if (fileHistory.size() < MAX_HISTORY_PER_PATH) {
                        fileHistory.add(summary);
                    }
                }
                for (String changedPath : changed) {
                    if (!targets.contains(changedPath)) {
                        coChanges.merge(changedPath, 1, Integer::sum);
                    }
                }
            }

            List<ContextFragment> fragments = new ArrayList<>();
            addRecentCommitsFragment(fragments, related, targets);
            addHistoryFragment(fragments, history);
            addWorkingTreeFragment(fragments, git.status().call(), targets);
            int coChangeLinks = addCoChangesFragment(fragments, coChanges);

            return new GitContextResult(
                    fragments,
                    true,
                    true,
                    commitsInspected,
                    related.size(),
                    coChangeLinks,
                    diagnostics);
        } catch (RepositoryNotFoundException exception) {
            return GitContextResult.unavailable("aucun repository Git local détecté");
        } catch (Exception exception) {
            diagnostics.add("contexte Git indisponible : " + safeMessage(exception));
            return new GitContextResult(List.of(), true, false, commitsInspected, 0, 0, diagnostics);
        }
    }

    private static Repository openRepository(Path projectRoot) throws IOException {
        return new FileRepositoryBuilder()
                .findGitDir(projectRoot.toFile())
                .setMustExist(true)
                .build();
    }

    private static Set<String> changedPaths(
            DiffFormatter formatter,
            RevCommit parent,
            RevCommit commit) throws IOException {
        Set<String> changed = new LinkedHashSet<>();
        for (DiffEntry entry : formatter.scan(parent.getTree(), commit.getTree())) {
            if (!DiffEntry.DEV_NULL.equals(entry.getOldPath())) {
                changed.add(entry.getOldPath());
            }
            if (!DiffEntry.DEV_NULL.equals(entry.getNewPath())) {
                changed.add(entry.getNewPath());
            }
        }
        return changed;
    }

    private static void addRecentCommitsFragment(
            List<ContextFragment> fragments,
            List<CommitSummary> commits,
            Set<String> targets) {
        if (commits.isEmpty()) {
            return;
        }
        StringBuilder content = new StringBuilder("# Commits Git récents liés au contexte\n\n");
        content.append("Chemins cibles : ").append(String.join(", ", targets)).append("\n\n");
        for (CommitSummary commit : commits.stream().limit(8).toList()) {
            content.append("- ")
                    .append(commit.shortId())
                    .append(" | ")
                    .append(commit.when())
                    .append(" | ")
                    .append(commit.message())
                    .append(" | ")
                    .append(String.join(", ", commit.paths().stream().limit(6).toList()))
                    .append('\n');
        }
        fragments.add(fragment(
                Path.of(".nexus", "git", "recent-commits.md"),
                content.toString(),
                0.78d,
                List.of(
                        "commits Git locaux touchant les chemins candidats",
                        "historique borné aux commits récents",
                        "provider : local-git")));
    }

    private static void addHistoryFragment(
            List<ContextFragment> fragments,
            Map<String, List<CommitSummary>> history) {
        if (history.isEmpty()) {
            return;
        }
        StringBuilder content = new StringBuilder("# Historique Git court des fichiers cibles\n\n");
        history.entrySet().stream()
                .limit(MAX_TARGET_HISTORY_PATHS)
                .forEach(entry -> {
                    content.append("## ").append(entry.getKey()).append('\n');
                    for (CommitSummary commit : entry.getValue()) {
                        content.append("- ")
                                .append(commit.shortId())
                                .append(" | ")
                                .append(commit.when())
                                .append(" | ")
                                .append(commit.message())
                                .append('\n');
                    }
                    content.append('\n');
                });
        fragments.add(fragment(
                Path.of(".nexus", "git", "file-history.md"),
                content.toString(),
                0.70d,
                List.of(
                        "historique limité des fichiers les mieux classés",
                        "provider : local-git")));
    }

    private static void addWorkingTreeFragment(
            List<ContextFragment> fragments,
            Status status,
            Set<String> targets) {
        List<String> changes = new ArrayList<>();
        collectStatus(changes, "ajouté", status.getAdded(), targets);
        collectStatus(changes, "modifié", status.getModified(), targets);
        collectStatus(changes, "changé dans l'index", status.getChanged(), targets);
        collectStatus(changes, "supprimé", status.getRemoved(), targets);
        collectStatus(changes, "manquant", status.getMissing(), targets);
        collectStatus(changes, "non suivi", status.getUntracked(), targets);
        if (changes.isEmpty()) {
            return;
        }
        String content = "# Diff local pertinent\n\n"
                + "Résumé des changements locaux limités aux chemins candidats :\n\n"
                + String.join("\n", changes)
                + "\n";
        fragments.add(fragment(
                Path.of(".nexus", "git", "working-tree-diff.md"),
                content,
                0.85d,
                List.of(
                        "changements locaux liés aux chemins candidats",
                        "aucun diff d'un fichier non ciblé n'est injecté",
                        "provider : local-git")));
    }

    private static int addCoChangesFragment(
            List<ContextFragment> fragments,
            Map<String, Integer> coChanges) {
        List<Map.Entry<String, Integer>> top = coChanges.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_CO_CHANGES)
                .toList();
        if (top.isEmpty()) {
            return 0;
        }
        StringBuilder content = new StringBuilder("# Fichiers fréquemment modifiés avec les chemins cibles\n\n");
        for (Map.Entry<String, Integer> entry : top) {
            content.append("- ")
                    .append(entry.getKey())
                    .append(" : ")
                    .append(entry.getValue())
                    .append(" commit(s) commun(s)\n");
        }
        fragments.add(fragment(
                Path.of(".nexus", "git", "co-changes.md"),
                content.toString(),
                0.60d,
                List.of(
                        "co-changements observés dans les commits récents",
                        "corrélation historique, pas dépendance métier garantie",
                        "provider : local-git")));
        return top.size();
    }

    private static void collectStatus(
            List<String> output,
            String label,
            Set<String> statusPaths,
            Set<String> targets) {
        statusPaths.stream()
                .filter(targets::contains)
                .sorted()
                .forEach(path -> output.add("- " + label + " : " + path));
    }

    private static ContextFragment fragment(
            Path path,
            String content,
            double score,
            List<String> reasons) {
        int lines = Math.max(1, content.split("\\R", -1).length);
        return new ContextFragment(
                CandidateType.GIT,
                path,
                null,
                1,
                lines,
                content,
                score,
                Map.of("gitContextScore", score),
                reasons);
    }

    private static Set<String> normalizedTargets(List<Path> paths) {
        Set<String> targets = new LinkedHashSet<>();
        for (Path path : paths) {
            String normalized = path.normalize().toString().replace('\\', '/');
            if (!normalized.isBlank() && !normalized.startsWith("../")) {
                targets.add(normalized);
            }
        }
        return targets;
    }

    private static Set<String> intersection(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : left) {
            if (right.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private record CommitSummary(
            String shortId,
            Instant when,
            String message,
            List<String> paths) {
    }
}
