package com.nexus.context.source.git;

import com.nexus.context.source.ContextDiscoveryBudget;
import com.nexus.context.source.ContextDiscoveryLimitExceededException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Cache mémoire borné du contexte Git pour les processus NEXUS longue durée.
 *
 * <p>Le résultat n'est réutilisé que si HEAD, le statut ciblé et les diffs
 * staged/unstaged des chemins cibles sont inchangés. Le cache ne persiste rien
 * sur disque et ne remplace jamais {@link LocalGitContextSourceProvider} comme
 * source de vérité.</p>
 */
public final class PersistentGitContextSourceProvider implements GitContextSourceProvider {

    static final int DEFAULT_CAPACITY = 16;
    private static final Path CACHE_VALIDATION_WORK = Path.of(".nexus", "git", "cache-validation");

    private final GitContextSourceProvider delegate;
    private final int capacity;
    private final LinkedHashMap<CacheKey, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private int hits;
    private int misses;
    private int evictions;

    public PersistentGitContextSourceProvider() {
        this(new LocalGitContextSourceProvider(), DEFAULT_CAPACITY);
    }

    PersistentGitContextSourceProvider(GitContextSourceProvider delegate, int capacity) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity doit être strictement positive");
        }
        this.capacity = capacity;
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public GitContextResult discover(GitContextQuery query) throws IOException {
        Objects.requireNonNull(query, "query");
        if (query.targetPaths().isEmpty()) {
            return delegate.discover(query);
        }

        Fingerprint fingerprint;
        try {
            fingerprint = Fingerprint.capture(query);
        } catch (ContextDiscoveryLimitExceededException limitExceeded) {
            throw limitExceeded;
        } catch (IOException | RuntimeException fingerprintFailure) {
            return delegate.discover(query);
        }

        CacheKey key = CacheKey.from(query);
        synchronized (entries) {
            Entry existing = entries.get(key);
            if (existing != null && existing.fingerprint().equals(fingerprint)) {
                hits++;
                return existing.result();
            }
        }

        GitContextResult result = delegate.discover(query);
        synchronized (entries) {
            misses++;
            if (!result.repositoryAvailable()) {
                entries.remove(key);
                return result;
            }
            if (!entries.containsKey(key) && entries.size() >= capacity) {
                var iterator = entries.entrySet().iterator();
                iterator.next();
                iterator.remove();
                evictions++;
            }
            entries.put(key, new Entry(fingerprint, result));
        }
        return result;
    }

    CacheStats stats() {
        synchronized (entries) {
            return new CacheStats(entries.size(), hits, misses, evictions);
        }
    }

    record CacheStats(int entries, int hits, int misses, int evictions) {
    }

    private record Entry(Fingerprint fingerprint, GitContextResult result) {
    }

    private record CacheKey(String projectRoot, String query, List<String> targets, boolean explain) {
        static CacheKey from(GitContextQuery query) {
            return new CacheKey(
                    query.project().rootPath().toAbsolutePath().normalize().toString(),
                    query.query(),
                    query.targetPaths().stream()
                            .map(PersistentGitContextSourceProvider::gitPath)
                            .sorted()
                            .toList(),
                    query.explain());
        }
    }

    private record Fingerprint(
            String workTree,
            String head,
            String status,
            String stagedDiff,
            String unstagedDiff) {

        static Fingerprint capture(GitContextQuery query) throws IOException {
            Path projectRoot = query.project().rootPath().toRealPath();
            try (Repository repository = new FileRepositoryBuilder()
                    .findGitDir(projectRoot.toFile())
                    .setMustExist(true)
                    .build();
                 Git git = new Git(repository)) {
                if (repository.isBare()) {
                    throw new IOException("Le cache Git nécessite un worktree local");
                }
                Path workTree = repository.getWorkTree().toPath().toRealPath();
                if (!projectRoot.startsWith(workTree)) {
                    throw new IOException("La racine projet est hors du worktree Git détecté");
                }

                List<String> targets = gitTargets(projectRoot, workTree, query.targetPaths());
                if (targets.isEmpty()) {
                    throw new IOException("Aucun chemin Git ciblé ne peut être validé");
                }

                ObjectId headId = repository.resolve(Constants.HEAD);
                String head = headId == null ? "unborn" : headId.name();

                query.discoveryBudget().visit(CACHE_VALIDATION_WORK);
                String status = statusSignature(git, targets);
                query.discoveryBudget().visit(CACHE_VALIDATION_WORK);
                String staged = diffDigest(git, targets, true, query.discoveryBudget());
                query.discoveryBudget().visit(CACHE_VALIDATION_WORK);
                String unstaged = diffDigest(git, targets, false, query.discoveryBudget());
                query.discoveryBudget().checkpoint();

                return new Fingerprint(workTree.toString(), head, status, staged, unstaged);
            } catch (GitAPIException exception) {
                throw new IOException("Impossible de valider le cache de contexte Git", exception);
            }
        }

        private static List<String> gitTargets(Path projectRoot, Path workTree, List<Path> targets) {
            String projectPrefix = gitPath(workTree.relativize(projectRoot));
            List<String> result = new ArrayList<>(targets.size());
            for (Path target : targets) {
                Path normalized = target.normalize();
                if (normalized.isAbsolute()) {
                    Path absolute = normalized.toAbsolutePath().normalize();
                    if (!absolute.startsWith(projectRoot)) {
                        continue;
                    }
                    normalized = projectRoot.relativize(absolute);
                }
                String relative = gitPath(normalized);
                if (relative.isBlank() || relative.equals("..") || relative.startsWith("../")) {
                    continue;
                }
                result.add(projectPrefix.isBlank() ? relative : projectPrefix + "/" + relative);
            }
            return result.stream().distinct().sorted().toList();
        }

        private static String statusSignature(Git git, List<String> targets) throws GitAPIException {
            var command = git.status();
            targets.forEach(command::addPath);
            Status status = command.call();
            return String.join("|",
                    signature("added", status.getAdded()),
                    signature("changed", status.getChanged()),
                    signature("modified", status.getModified()),
                    signature("missing", status.getMissing()),
                    signature("removed", status.getRemoved()),
                    signature("untracked", status.getUntracked()),
                    signature("conflicting", status.getConflicting()));
        }

        private static String signature(String label, Set<String> paths) {
            return label + "=" + String.join(",", paths.stream().sorted().toList());
        }

        private static String diffDigest(
                Git git,
                List<String> targets,
                boolean cached,
                ContextDiscoveryBudget budget) throws GitAPIException, ContextDiscoveryLimitExceededException {
            DigestOutput output = new DigestOutput(budget);
            try {
                git.diff()
                        .setCached(cached)
                        .setPathFilter(PathFilterGroup.createFromStrings(targets))
                        .setOutputStream(output)
                        .call();
            } catch (GitAPIException | RuntimeException failure) {
                ContextDiscoveryLimitExceededException limitExceeded = discoveryLimitCause(failure);
                if (limitExceeded != null) {
                    throw limitExceeded;
                }
                throw failure;
            }
            return output.hexDigest();
        }

        private static ContextDiscoveryLimitExceededException discoveryLimitCause(Throwable failure) {
            Throwable current = failure;
            while (current != null) {
                if (current instanceof ContextDiscoveryLimitExceededException limitExceeded) {
                    return limitExceeded;
                }
                current = current.getCause();
            }
            return null;
        }
    }

    private static final class DigestOutput extends OutputStream {
        private final MessageDigest digest;
        private final ContextDiscoveryBudget budget;

        private DigestOutput(ContextDiscoveryBudget budget) {
            this.budget = Objects.requireNonNull(budget, "budget");
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 indisponible", impossible);
            }
        }

        @Override
        public void write(int value) throws IOException {
            budget.bytes(CACHE_VALIDATION_WORK, 1L);
            digest.update((byte) value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return;
            }
            budget.bytes(CACHE_VALIDATION_WORK, length);
            digest.update(bytes, offset, length);
        }

        private String hexDigest() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    private static String gitPath(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }
}
