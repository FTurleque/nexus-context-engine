package com.nexus.context.source.git;

import com.nexus.paths.RepositoryPath;

import com.nexus.project.FederatedScopePolicy;
import com.nexus.project.ProjectDescriptor;
import com.nexus.search.candidate.CandidateEnricher;
import com.nexus.search.candidate.SearchCandidate;
import com.nexus.search.candidate.SearchSignals;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Ajoute un faible signal de récence Git aux candidats déjà découverts.
 * Le provider est strictement local, en lecture seule et son coût est borné
 * par les seuls chemins candidats au lieu de scanner les diffs complets.
 *
 * <p>Le signal est fail-open : un incident JGit laisse les candidats inchangés, émet un
 * enregistrement {@code WARNING} qui ne nomme que le type d'exception et, si une explication est
 * demandée, la raison {@value #UNAVAILABLE_REASON}. Un dépassement de budget (chemins candidats ou
 * chemins modifiés) laisse aussi les candidats inchangés, avec la raison {@value #BUDGET_REASON}.
 * Un projet hors Git reste silencieux.</p>
 */
public final class GitRecencyCandidateEnricher implements CandidateEnricher {

    static final String UNAVAILABLE_REASON = "récence Git indisponible";
    static final String BUDGET_REASON = "récence Git ignorée : budget de chemins dépassé";

    private static final System.Logger LOGGER = System.getLogger(GitRecencyCandidateEnricher.class.getName());

    static final int MAX_COMMITS = 50;
    static final int MAX_CANDIDATE_PATHS = 512;
    static final int MAX_CHANGED_PATHS_PER_COMMIT = 1_024;
    static final int MAX_CUMULATIVE_CHANGED_PATHS = 4_096;
    /** Capacité du cache de récence : une entrée par projet d'un scope fédéré maximal (BE-0065). */
    static final int DEFAULT_RECENCY_CACHE_CAPACITY = FederatedScopePolicy.MAX_PROJECTS;

    private final int recencyCapacity;
    private final LinkedHashMap<RecencyKey, RecencyIndex> recencyCache = new LinkedHashMap<>(16, 0.75f, true);
    private final AtomicInteger logScans = new AtomicInteger();

    public GitRecencyCandidateEnricher() {
        this(DEFAULT_RECENCY_CACHE_CAPACITY);
    }

    GitRecencyCandidateEnricher(int recencyCapacity) {
        if (recencyCapacity <= 0) {
            throw new IllegalArgumentException("recencyCapacity doit être strictement positive");
        }
        this.recencyCapacity = recencyCapacity;
    }

    /** Nombre de parcours du journal des commits effectués par cette instance (pour les tests). */
    int logScans() {
        return logScans.get();
    }

    /** Clé du cache de récence : dépôt (répertoire Git) et HEAD. */
    private record RecencyKey(String gitDir, String head) {
    }

    /**
     * Récence par chemin Git des derniers commits, ou {@link #TOO_LARGE} quand les bornes de
     * chemins modifiés interdisent de la mémoriser.
     */
    private record RecencyIndex(boolean cacheable, Map<String, Double> scoreByGitPath) {
        static final RecencyIndex TOO_LARGE = new RecencyIndex(false, Map.of());

        Map<String, Double> scoresFor(Map<String, String> projectPathByGitPath) {
            Map<String, Double> recency = new HashMap<>();
            projectPathByGitPath.forEach((gitPath, projectPath) -> {
                Double score = scoreByGitPath.get(gitPath);
                if (score != null) {
                    recency.put(projectPath, score);
                }
            });
            return recency;
        }
    }

    @Override
    public List<SearchCandidate> enrich(ProjectDescriptor project, List<SearchCandidate> candidates) throws IOException {
        return enrich(project, candidates, reason -> { });
    }

    @Override
    public List<SearchCandidate> enrich(
            ProjectDescriptor project,
            List<SearchCandidate> candidates,
            Consumer<String> explanationReasons) throws IOException {
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
        if (candidatesByProjectPath.size() > MAX_CANDIDATE_PATHS) {
            explanationReasons.accept(BUDGET_REASON);
            return candidates;
        }

        Optional<Map<String, Double>> recency;
        try {
            recency = recency(project.rootPath(), candidatesByProjectPath.keySet());
        } catch (RepositoryNotFoundException exception) {
            // Cas normal d'un projet hors Git : pas d'avertissement, une trace de diagnostic.
            LOGGER.log(System.Logger.Level.DEBUG, "Projet hors dépôt Git : récence Git non calculée");
            return candidates;
        } catch (Exception exception) {
            // La récence Git est un signal optionnel : tout incident reste fail-open pour ne jamais
            // rendre la recherche principale indisponible. Le message de l'exception peut citer le
            // chemin du dépôt : seul son type est journalisé.
            LOGGER.log(System.Logger.Level.WARNING,
                    "Récence Git indisponible, signal ignoré : {0}", exception.getClass().getName());
            explanationReasons.accept(UNAVAILABLE_REASON);
            return candidates;
        }
        if (recency.isEmpty()) {
            explanationReasons.accept(BUDGET_REASON);
            return candidates;
        }
        return recency.get().isEmpty() ? candidates : withRecency(project, candidates, recency.get());
    }

    /**
     * Score de récence par chemin projet sur les {@value #MAX_COMMITS} derniers commits, ou
     * un résultat vide quand le nombre de chemins modifiés dépasse le budget.
     */
    private Optional<Map<String, Double>> recency(Path projectRoot, Set<String> projectPaths)
            throws IOException, GitAPIException {
        try (Repository repository = openRepository(projectRoot)) {
            String projectPrefix = projectPrefix(repository, projectRoot);
            Map<String, String> projectPathByGitPath = new HashMap<>();
            for (String projectPath : projectPaths) {
                projectPathByGitPath.put(toGitPath(projectPrefix, projectPath), projectPath);
            }

            // Cache par (dépôt, HEAD) : tant que HEAD ne change pas, le journal n'est pas relu.
            ObjectId head = repository.resolve(Constants.HEAD);
            if (head != null) {
                RecencyIndex index = recencyIndex(repository, head);
                if (index.cacheable()) {
                    return Optional.of(index.scoresFor(projectPathByGitPath));
                }
            }
            // Historique trop large pour être mémorisé : parcours filtré par les chemins candidats.
            return filteredRecency(repository, projectPathByGitPath);
        }
    }

    private RecencyIndex recencyIndex(Repository repository, ObjectId head) throws IOException, GitAPIException {
        RecencyKey key = new RecencyKey(
                repository.getDirectory().toPath().toAbsolutePath().normalize().toString(), head.name());
        synchronized (recencyCache) {
            RecencyIndex cached = recencyCache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        RecencyIndex built = buildRecencyIndex(repository);
        synchronized (recencyCache) {
            // Un nouveau HEAD invalide l'entrée précédente du même dépôt.
            recencyCache.keySet().removeIf(existing -> existing.gitDir().equals(key.gitDir()));
            if (recencyCache.size() >= recencyCapacity) {
                var iterator = recencyCache.entrySet().iterator();
                iterator.next();
                iterator.remove();
            }
            recencyCache.put(key, built);
        }
        return built;
    }

    /**
     * Chemins modifiés des {@value #MAX_COMMITS} derniers commits, sans filtre de candidats, dans
     * les bornes {@value #MAX_CHANGED_PATHS_PER_COMMIT} par commit et
     * {@value #MAX_CUMULATIVE_CHANGED_PATHS} cumulés. Au-delà, l'historique n'est pas mémorisable.
     */
    private RecencyIndex buildRecencyIndex(Repository repository) throws IOException, GitAPIException {
        logScans.incrementAndGet();
        Map<String, Double> scores = new HashMap<>();
        try (Git git = new Git(repository);
             RevWalk revWalk = new RevWalk(repository)) {
            int position = 0;
            int cumulativeChangedPaths = 0;
            for (RevCommit commit : git.log().setMaxCount(MAX_COMMITS).call()) {
                if (commit.getParentCount() == 0) {
                    position++;
                    continue;
                }
                RevCommit parent = revWalk.parseCommit(commit.getParent(0).getId());
                double score = recencyScore(position++);
                int commitChangedPaths = 0;
                try (TreeWalk treeWalk = new TreeWalk(repository)) {
                    // Même parcours que DiffFormatter.scan sans détection de renommage ni filtre.
                    treeWalk.setRecursive(true);
                    treeWalk.addTree(parent.getTree());
                    treeWalk.addTree(commit.getTree());
                    treeWalk.setFilter(TreeFilter.ANY_DIFF);
                    while (treeWalk.next()) {
                        commitChangedPaths++;
                        cumulativeChangedPaths++;
                        if (commitChangedPaths > MAX_CHANGED_PATHS_PER_COMMIT
                                || cumulativeChangedPaths > MAX_CUMULATIVE_CHANGED_PATHS) {
                            return RecencyIndex.TOO_LARGE;
                        }
                        scores.merge(treeWalk.getPathString(), score, Math::max);
                    }
                }
            }
        }
        return new RecencyIndex(true, Map.copyOf(scores));
    }

    private Optional<Map<String, Double>> filteredRecency(
            Repository repository,
            Map<String, String> projectPathByGitPath) throws IOException, GitAPIException {
        logScans.incrementAndGet();
        Map<String, Double> recency = new HashMap<>();
        try (Git git = new Git(repository);
             RevWalk revWalk = new RevWalk(repository);
             DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            diffFormatter.setRepository(repository);
            // La détection de renommage inspecte potentiellement de nombreux blobs sans bénéfice
            // pour ce signal : une modification du chemin courant reste visible comme ADD/DELETE.
            diffFormatter.setDetectRenames(false);
            diffFormatter.setPathFilter(PathFilterGroup.createFromStrings(projectPathByGitPath.keySet()));

            int position = 0;
            int cumulativeChangedPaths = 0;
            for (RevCommit commit : git.log().setMaxCount(MAX_COMMITS).call()) {
                if (commit.getParentCount() == 0) {
                    position++;
                    continue;
                }
                RevCommit parent = revWalk.parseCommit(commit.getParent(0).getId());
                List<DiffEntry> entries = diffFormatter.scan(parent.getTree(), commit.getTree());
                cumulativeChangedPaths += entries.size();
                if (entries.size() > MAX_CHANGED_PATHS_PER_COMMIT
                        || cumulativeChangedPaths > MAX_CUMULATIVE_CHANGED_PATHS) {
                    return Optional.empty();
                }

                double score = recencyScore(position++);
                for (DiffEntry entry : entries) {
                    addScore(recency, projectPathByGitPath, entry.getOldPath(), score);
                    addScore(recency, projectPathByGitPath, entry.getNewPath(), score);
                }
            }
        }
        return Optional.of(recency);
    }

    private static List<SearchCandidate> withRecency(
            ProjectDescriptor project,
            List<SearchCandidate> candidates,
            Map<String, Double> recency) {
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
        FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(projectRoot.toFile());
        if (builder.getGitDir() == null) {
            throw new RepositoryNotFoundException(projectRoot.toFile());
        }
        return builder.setMustExist(true).build();
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
        return RepositoryPath.encode(workTree.relativize(root));
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
        return RepositoryPath.encode(root.relativize(absolute));
    }
}
