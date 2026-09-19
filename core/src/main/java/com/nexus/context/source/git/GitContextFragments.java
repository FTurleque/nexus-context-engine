package com.nexus.context.source.git;

import com.nexus.context.ContextFragment;
import com.nexus.search.CandidateType;
import org.eclipse.jgit.api.Git;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.nexus.context.source.git.LocalGitContextSourceProvider.*;

/** Construction bornée des fragments Git locaux. */
final class GitContextFragments {
    static void addRecentCommitsFragment(
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
                Path.of(NEXUS_DIRECTORY, "git", "recent-commits.md"),
                content.toString(),
                0.78d,
                List.of(
                        "commits Git locaux touchant les chemins candidats",
                        "historique borné aux commits récents",
                        "provider : local-git")));
    }

    static void addHistoryFragment(
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
                Path.of(NEXUS_DIRECTORY, "git", "file-history.md"),
                content.toString(),
                0.70d,
                List.of(
                        "historique limité des fichiers les mieux classés",
                        "provider : local-git")));
    }

    static int addCoChangesFragment(
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
                Path.of(NEXUS_DIRECTORY, "git", "co-changes.md"),
                content.toString(),
                0.60d,
                List.of(
                        "co-changements observés dans les commits récents",
                        "corrélation historique, pas dépendance métier garantie",
                        "provider : local-git")));
        return top.size();
    }

    static ContextFragment fragment(
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
    record CommitSummary(
            String shortId,
            Instant when,
            String message,
            List<String> paths) {
    }
}
