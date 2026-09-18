package com.nexus.context.source.git;

import com.nexus.context.ContextFragment;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.StatusCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.nexus.context.source.git.LocalGitContextSourceProvider.*;
import static com.nexus.context.source.git.GitContextFragments.fragment;

/** Construction bornée des fragments Git locaux. */
final class GitWorkingTreeContext {
    static Status targetStatus(Git git, Set<String> gitTargets) throws GitAPIException {
        StatusCommand command = git.status();
        for (String gitTarget : gitTargets) {
            command.addPath(gitTarget);
        }
        return command.call();
    }

    static void addWorkingTreeFragment(
            List<ContextFragment> fragments,
            Git git,
            Status status,
            Map<String, String> projectPathByGitTarget,
            String projectPrefix,
            GitContextQuery query) throws IOException, GitAPIException {
        List<String> changes = new ArrayList<>();
        collectStatus(changes, "ajouté", status.getAdded(), projectPathByGitTarget);
        collectStatus(changes, "modifié", status.getModified(), projectPathByGitTarget);
        collectStatus(changes, "changé dans l'index", status.getChanged(), projectPathByGitTarget);
        collectStatus(changes, "supprimé", status.getRemoved(), projectPathByGitTarget);
        collectStatus(changes, "manquant", status.getMissing(), projectPathByGitTarget);
        collectStatus(changes, "non suivi", status.getUntracked(), projectPathByGitTarget);

        Set<String> gitTargets = projectPathByGitTarget.keySet();
        String unstagedPatch = formatTargetDiff(git, gitTargets, projectPrefix, false, query);
        String stagedPatch = formatTargetDiff(git, gitTargets, projectPrefix, true, query);

        if (changes.isEmpty() && unstagedPatch.isBlank() && stagedPatch.isBlank()) {
            return;
        }

        StringBuilder content = new StringBuilder("# Diff local pertinent\n\n");
        if (!unstagedPatch.isBlank()) {
            content.append("## Patch non indexé\n\n```diff\n")
                    .append(unstagedPatch)
                    .append("\n```\n\n");
        }
        if (!stagedPatch.isBlank()) {
            content.append("## Patch indexé\n\n```diff\n")
                    .append(stagedPatch)
                    .append("\n```\n\n");
        }
        if (!changes.isEmpty()) {
            content.append("## Résumé de statut\n\n")
                    .append(String.join("\n", changes))
                    .append('\n');
        }

        fragments.add(fragment(
                Path.of(NEXUS_DIRECTORY, "git", "working-tree-diff.md"),
                content.toString(),
                0.85d,
                List.of(
                        "patches et changements locaux liés aux chemins candidats",
                        "aucun diff d'un fichier non ciblé n'est injecté",
                        "diff local borné avant allocation à " + MAX_LOCAL_DIFF_CHARS + " caractères par zone",
                        "provider : local-git")));
    }

    static String formatTargetDiff(
            Git git,
            Set<String> gitTargets,
            String projectPrefix,
            boolean cached,
            GitContextQuery query) throws IOException, GitAPIException {
        if (gitTargets.isEmpty()) {
            return "";
        }

        BoundedOutput output = new BoundedOutput(MAX_LOCAL_DIFF_BYTES);
        git.diff()
                .setCached(cached)
                .setPathFilter(PathFilterGroup.createFromStrings(gitTargets))
                .setOutputStream(output)
                .call();

        query.discoveryBudget().bytes(GIT_DIFF_WORK, output.size());
        String patch = relativizePatch(projectPrefix, output.toUtf8String());
        boolean truncated = output.truncated() || patch.length() > MAX_LOCAL_DIFF_CHARS;
        if (patch.length() > MAX_LOCAL_DIFF_CHARS) {
            patch = patch.substring(0, MAX_LOCAL_DIFF_CHARS);
        }
        patch = patch.stripTrailing();
        return truncated
                ? patch + "\n... [diff Git tronqué par NEXUS]"
                : patch;
    }

    static String relativizePatch(String projectPrefix, String patch) {
        if (projectPrefix.isBlank()) {
            return patch;
        }
        return patch
                .replace("a/" + projectPrefix + "/", "a/")
                .replace("b/" + projectPrefix + "/", "b/");
    }

    static void collectStatus(
            List<String> output,
            String label,
            Set<String> statusPaths,
            Map<String, String> projectPathByGitTarget) {
        statusPaths.stream()
                .filter(projectPathByGitTarget::containsKey)
                .map(projectPathByGitTarget::get)
                .sorted()
                .forEach(path -> output.add("- " + label + " : " + path));
    }
    private static final class BoundedOutput extends OutputStream {
        private final byte[] buffer;
        private int size;
        private boolean truncated;

        private BoundedOutput(int capacity) {
            this.buffer = new byte[capacity];
        }

        @Override
        public void write(int value) {
            if (size < buffer.length) {
                buffer[size++] = (byte) value;
            } else {
                truncated = true;
            }
        }

        @Override
        public void write(byte[] source, int offset, int length) {
            Objects.requireNonNull(source, "source");
            Objects.checkFromIndexSize(offset, length, source.length);
            int remaining = buffer.length - size;
            int copied = Math.min(remaining, length);
            if (copied > 0) {
                System.arraycopy(source, offset, buffer, size, copied);
                size += copied;
            }
            if (copied < length) {
                truncated = true;
            }
        }

        int size() {
            return size;
        }

        boolean truncated() {
            return truncated;
        }

        String toUtf8String() {
            return new String(buffer, 0, size, StandardCharsets.UTF_8);
        }
    }

}
