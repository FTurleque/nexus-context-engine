package com.nexus.context.source.git;

import com.nexus.paths.RepositoryPath;

import com.nexus.context.ContextFragment;
import com.nexus.context.source.ContextDiscoveryLimitExceededException;
import org.eclipse.jgit.api.Git;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.nexus.context.source.git.GitContextFragments.*;
import static com.nexus.context.source.git.GitWorkingTreeContext.*;

/**
 * Provider Git local, borné et strictement en lecture seule.
 */

public final class LocalGitContextSourceProvider implements GitContextSourceProvider {

    static final int MAX_COMMITS = 50;
    static final int MAX_TARGET_HISTORY_PATHS = 5;
    static final int MAX_HISTORY_PER_PATH = 5;
    static final int MAX_CO_CHANGES = 8;
    static final int MAX_CHANGED_PATHS_PER_COMMIT = 2_000;
    static final int MAX_CUMULATIVE_CHANGED_PATHS = 10_000;
    static final int MAX_LOCAL_DIFF_CHARS = 6_000;
    static final int MAX_LOCAL_DIFF_BYTES = MAX_LOCAL_DIFF_CHARS * 4 + 1_024;

    static final String NEXUS_DIRECTORY = ".nexus";
    private static final Path GIT_HISTORY_WORK = Path.of(NEXUS_DIRECTORY, "git", "history");
    static final Path GIT_DIFF_WORK = Path.of(NEXUS_DIRECTORY, "git", "working-tree-diff");

    @Override
    public String id() {
        return "local-git";
    }

    @Override
    public GitContextResult discover(GitContextQuery query) throws IOException {
        Set<String> projectTargets = normalizedTargets(query.targetPaths());
        if (projectTargets.isEmpty()) {
            return new GitContextResult(List.of(), true, true, 0, 0, 0, List.of());
        }

        List<String> diagnostics = new ArrayList<>();
        List<CommitSummary> related = new ArrayList<>();
        Map<String, List<CommitSummary>> history = new LinkedHashMap<>();
        Map<String, Integer> coChanges = new HashMap<>();
        int commitsInspected = 0;
        int cumulativeChangedPaths = 0;

        try (Repository repository = openRepository(query.project().rootPath());
             Git git = new Git(repository);
             RevWalk revWalk = new RevWalk(repository);
             DiffFormatter historyDiffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            historyDiffFormatter.setRepository(repository);
            historyDiffFormatter.setDetectRenames(true);

            String projectPrefix = projectPrefix(repository, query.project().rootPath());
            Map<String, String> projectPathByGitTarget = new LinkedHashMap<>();
            for (String projectTarget : projectTargets) {
                projectPathByGitTarget.put(toGitPath(projectPrefix, projectTarget), projectTarget);
            }
            Set<String> gitTargets = projectPathByGitTarget.keySet();

            for (RevCommit commit : git.log().setMaxCount(MAX_COMMITS).call()) {
                query.discoveryBudget().checkpoint();
                query.discoveryBudget().candidate(GIT_HISTORY_WORK);
                commitsInspected++;
                if (commit.getParentCount() == 0) {
                    continue;
                }

                RevCommit parent = revWalk.parseCommit(commit.getParent(0).getId());
                Set<String> changedGitPaths = changedPaths(historyDiffFormatter, parent, commit, query);
                cumulativeChangedPaths += changedGitPaths.size();
                if (cumulativeChangedPaths > MAX_CUMULATIVE_CHANGED_PATHS) {
                    throw new ContextDiscoveryLimitExceededException(
                            "Budget Git dépassé: plus de " + MAX_CUMULATIVE_CHANGED_PATHS
                                    + " chemins modifiés dans la fenêtre d'historique");
                }
                Set<String> touchedGitTargets = intersection(changedGitPaths, gitTargets);
                if (touchedGitTargets.isEmpty()) {
                    continue;
                }

                List<String> changedProjectPaths = changedGitPaths.stream()
                        .map(path -> toProjectPath(projectPrefix, path))
                        .filter(Objects::nonNull)
                        .sorted()
                        .toList();
                CommitSummary summary = new CommitSummary(
                        commit.getId().abbreviate(8).name(),
                        Objects.requireNonNull(
                                commit.getCommitterIdent(),
                                "Git commit without committer identity")
                                .getWhenAsInstant(),
                        commit.getShortMessage(),
                        changedProjectPaths);
                related.add(summary);

                for (String gitTarget : touchedGitTargets) {
                    String projectTarget = projectPathByGitTarget.get(gitTarget);
                    List<CommitSummary> fileHistory = history.computeIfAbsent(
                            projectTarget,
                            ignored -> new ArrayList<>());
                    if (fileHistory.size() < MAX_HISTORY_PER_PATH) {
                        fileHistory.add(summary);
                    }
                }

                for (String changedGitPath : changedGitPaths) {
                    String projectPath = toProjectPath(projectPrefix, changedGitPath);
                    if (projectPath != null && !projectTargets.contains(projectPath)) {
                        coChanges.merge(projectPath, 1, Integer::sum);
                    }
                }
            }

            List<ContextFragment> fragments = new ArrayList<>();
            addRecentCommitsFragment(fragments, related, projectTargets);
            addHistoryFragment(fragments, history);
            addWorkingTreeFragment(
                    fragments,
                    git,
                    targetStatus(git, gitTargets),
                    projectPathByGitTarget,
                    projectPrefix,
                    query);
            int coChangeLinks = addCoChangesFragment(fragments, coChanges);

            return new GitContextResult(
                    fragments,
                    true,
                    true,
                    commitsInspected,
                    related.size(),
                    coChangeLinks,
                    diagnostics);
        } catch (ContextDiscoveryLimitExceededException limitExceeded) {
            throw limitExceeded;
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
            RevCommit commit,
            GitContextQuery query) throws IOException {
        Set<String> changed = new LinkedHashSet<>();
        for (DiffEntry entry : formatter.scan(parent.getTree(), commit.getTree())) {
            query.discoveryBudget().visit(GIT_HISTORY_WORK);
            if (!DiffEntry.DEV_NULL.equals(entry.getOldPath())) {
                changed.add(entry.getOldPath());
            }
            if (!DiffEntry.DEV_NULL.equals(entry.getNewPath())) {
                changed.add(entry.getNewPath());
            }
            if (changed.size() > MAX_CHANGED_PATHS_PER_COMMIT) {
                throw new ContextDiscoveryLimitExceededException(
                        "Budget Git dépassé: un commit contient plus de "
                                + MAX_CHANGED_PATHS_PER_COMMIT + " chemins modifiés");
            }
        }
        return changed;
    }

    private static Set<String> normalizedTargets(List<Path> paths) {
        Set<String> targets = new LinkedHashSet<>();
        for (Path path : paths) {
            String normalized = RepositoryPath.encode(path.normalize());
            if (!normalized.isBlank() && !normalized.startsWith("../")) {
                targets.add(normalized);
            }
        }
        return targets;
    }

    private static String projectPrefix(Repository repository, Path projectRoot) throws IOException {
        if (repository.isBare()) {
            throw new IOException("Le contexte Git nécessite un worktree local");
        }
        Path workTree = repository.getWorkTree().toPath().toRealPath();
        Path root = projectRoot.toRealPath();
        if (!root.startsWith(workTree)) {
            throw new IOException("La racine projet est hors du worktree Git détecté");
        }
        return RepositoryPath.encode(workTree.relativize(root));
    }

    private static String toGitPath(String projectPrefix, String projectPath) {
        return projectPrefix.isBlank() ? projectPath : projectPrefix + "/" + projectPath;
    }

    private static String toProjectPath(String projectPrefix, String gitPath) {
        if (gitPath == null || DiffEntry.DEV_NULL.equals(gitPath)) {
            return null;
        }
        if (projectPrefix.isBlank()) {
            return gitPath;
        }
        String prefix = projectPrefix + "/";
        return gitPath.startsWith(prefix) ? gitPath.substring(prefix.length()) : null;
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
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return exception.getClass().getSimpleName() + ": " + message;
    }

}
