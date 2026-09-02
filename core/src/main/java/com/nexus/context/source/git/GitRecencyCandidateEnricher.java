package com.nexus.context.source.git;

import com.nexus.project.ProjectDescriptor;
import com.nexus.search.CandidateEnricher;
import com.nexus.search.SearchCandidate;
import com.nexus.search.SearchSignals;
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
import java.util.List;
import java.util.Map;

/**
 * Ajoute un faible signal de récence Git aux candidats déjà découverts.
 * Le provider est strictement local et en lecture seule.
 */
public final class GitRecencyCandidateEnricher implements CandidateEnricher {

    static final int MAX_COMMITS = 50;

    @Override
    public List<SearchCandidate> enrich(ProjectDescriptor project, List<SearchCandidate> candidates) throws IOException {
        if (candidates.isEmpty()) {
            return candidates;
        }

        Map<String, SearchCandidate> candidatesByProjectPath = new LinkedHashMap<>();
        for (SearchCandidate candidate : candidates) {
            String relativePath = relativePath(project, candidate.path());
            if (relativePath != null) {
                candidatesByProjectPath.putIfAbsent(relativePath, candidate);
            }
        }
        if (candidatesByProjectPath.isEmpty()) {
            return candidates;
        }

        Map<String, Double> recency = new HashMap<>();
        try (Repository repository = openRepository(project.rootPath());
             Git git = new Git(repository);
             RevWalk revWalk = new RevWalk(repository);
             DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            diffFormatter.setRepository(repository);
            diffFormatter.setDetectRenames(true);

            String projectPrefix = projectPrefix(repository, project.rootPath());
            Map<String, String> projectPathByGitPath = new HashMap<>();
            for (String projectPath : candidatesByProjectPath.keySet()) {
                projectPathByGitPath.put(toGitPath(projectPrefix, projectPath), projectPath);
            }

            int position = 0;
            for (RevCommit commit : git.log().setMaxCount(MAX_COMMITS).call()) {
                if (commit.getParentCount() == 0) {
                    position++;
                    continue;
                }
                RevCommit parent = revWalk.parseCommit(commit.getParent(0).getId());
                double score = recencyScore(position++);
                for (DiffEntry entry : diffFormatter.scan(parent.getTree(), commit.getTree())) {
                    addScore(recency, projectPathByGitPath, entry.getOldPath(), score);
                    addScore(recency, projectPathByGitPath, entry.getNewPath(), score);
                }
            }
        } catch (RepositoryNotFoundException exception) {
            return candidates;
        } catch (Exception exception) {
            return candidates;
        }

        if (recency.isEmpty()) {
            return candidates;
        }

        List<SearchCandidate> enriched = new ArrayList<>(candidates.size());
        for (SearchCandidate candidate : candidates) {
            String path = relativePath(project, candidate.path());
            double score = path == null ? 0.0d : recency.getOrDefault(path, 0.0d);
            if (score <= 0.0d) {
                enriched.add(candidate);
                continue;
            }
            Map<String, Double> signals = new LinkedHashMap<>(candidate.signals());
            signals.merge(SearchSignals.GIT_RECENCY, score, Math::max);
            enriched.add(new SearchCandidate(
                    candidate.id(),
                    candidate.type(),
                    candidate.path(),
                    candidate.symbol(),
                    candidate.excerpt(),
                    signals));
        }
        return List.copyOf(enriched);
    }

    private static Repository openRepository(Path projectRoot) throws IOException {
        return new FileRepositoryBuilder()
                .findGitDir(projectRoot.toFile())
                .setMustExist(true)
                .build();
    }

    private static void addScore(
            Map<String, Double> recency,
            Map<String, String> projectPathByGitPath,
            String gitPath,
            double score) {
        if (gitPath == null || DiffEntry.DEV_NULL.equals(gitPath)) {
            return;
        }
        String projectPath = projectPathByGitPath.get(gitPath);
        if (projectPath != null) {
            recency.merge(projectPath, score, Math::max);
        }
    }

    private static double recencyScore(int position) {
        if (MAX_COMMITS <= 1) {
            return 1.0d;
        }
        return Math.max(0.05d, 1.0d - ((double) position / (MAX_COMMITS - 1)));
    }

    private static String projectPrefix(Repository repository, Path projectRoot) {
        Path workTree = repository.getWorkTree().toPath().toAbsolutePath().normalize();
        Path root = projectRoot.toAbsolutePath().normalize();
        if (!root.startsWith(workTree)) {
            return "";
        }
        return workTree.relativize(root).toString().replace('\\', '/');
    }

    private static String toGitPath(String projectPrefix, String projectPath) {
        return projectPrefix.isBlank() ? projectPath : projectPrefix + "/" + projectPath;
    }

    private static String relativePath(ProjectDescriptor project, Path candidatePath) {
        Path root = project.rootPath().toAbsolutePath().normalize();
        Path absolute = candidatePath.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) {
            return null;
        }
        return root.relativize(absolute).toString().replace('\\', '/');
    }
}
