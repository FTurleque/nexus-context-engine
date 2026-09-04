package com.nexus.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nexus.context.source.git.GitContextQuery;
import com.nexus.context.source.git.GitContextResult;
import com.nexus.context.source.git.LocalGitContextSourceProvider;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Qualification hermétique du watch item #53.
 *
 * <p>Le prototype reste strictement test-only : il mémorise un résultat Git borné
 * et valide chaque hit par un fingerprint HEAD + status + diffs ciblés. Aucun
 * cache de production ni format persistant n'est introduit par ce benchmark.</p>
 */
@EnabledIfSystemProperty(named = "nexus.git.cache.benchmark.enabled", matches = "true")
class GitContextCacheQualificationBenchmarkTest {

    private static final int CI_REPOSITORIES = 6;
    private static final int FULL_REPOSITORIES = 12;
    private static final int CI_COMMITS = 24;
    private static final int FULL_COMMITS = 48;
    private static final int WARM_CYCLES = 5;
    private static final int CACHE_CAPACITY = 16;
    private static final double MIN_WARM_P95_IMPROVEMENT = 0.25d;

    @TempDir
    Path temporaryDirectory;

    @Test
    void measuresColdWarmCachingAndInvalidationContract() throws Exception {
        String profile = profile();
        int repositoryCount = profile.equals("full") ? FULL_REPOSITORIES : CI_REPOSITORIES;
        int commitsPerRepository = profile.equals("full") ? FULL_COMMITS : CI_COMMITS;
        List<RepoFixture> repositories = createRepresentativeRepositories(repositoryCount, commitsPerRepository);

        LocalGitContextSourceProvider baseline = new LocalGitContextSourceProvider();
        PrototypeCache warmCache = new PrototypeCache(CACHE_CAPACITY);
        for (RepoFixture fixture : repositories) {
            warmCache.discover(fixture.query());
        }

        Latency baselineWarm = measure(repositories, WARM_CYCLES, fixture -> baseline.discover(fixture.query()));
        Latency cachedWarm = measure(repositories, WARM_CYCLES, fixture -> warmCache.discover(fixture.query()));

        PrototypeCache coldCache = new PrototypeCache(CACHE_CAPACITY);
        Latency coldMiss = measure(repositories, 1, fixture -> coldCache.discover(fixture.query()));

        Map<String, Boolean> invalidation = new LinkedHashMap<>();
        invalidation.put("headCommit", qualifyHeadInvalidation());
        invalidation.put("stagedIndex", qualifyStagedInvalidation());
        invalidation.put("workingTree", qualifyWorkingTreeInvalidation());
        invalidation.put("rename", qualifyRenameInvalidation());
        invalidation.put("rebase", qualifyRebaseInvalidation());
        invalidation.put("linkedWorktreeIsolation", qualifyLinkedWorktreeIsolation());

        PrototypeCache bounded = new PrototypeCache(3);
        for (RepoFixture fixture : repositories.subList(0, Math.min(5, repositories.size()))) {
            bounded.discover(fixture.query());
        }
        boolean storageBounded = bounded.peakEntries() <= bounded.capacity()
                && bounded.entries() <= bounded.capacity()
                && (repositories.size() < 4 || bounded.evictions() > 0);
        assertTrue(storageBounded, "le prototype doit rester strictement borné");

        double warmP95Improvement = improvementRatio(baselineWarm.p95Micros(), cachedWarm.p95Micros());
        boolean invalidationQualified = invalidation.values().stream().allMatch(Boolean::booleanValue);
        boolean candidate = warmP95Improvement >= MIN_WARM_P95_IMPROVEMENT
                && invalidationQualified
                && storageBounded;

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("profile", profile);
        report.put("repositories", repositoryCount);
        report.put("commitsPerRepository", commitsPerRepository);
        report.put("protocol", Map.of(
                "warmCycles", WARM_CYCLES,
                "targetPathsPerRepository", 1,
                "cacheType", "test_only_bounded_in_process_result_cache",
                "fingerprint", "worktree+HEAD+target-status+staged-diff+unstaged-diff"));
        report.put("latency", Map.of(
                "baselineWarm", baselineWarm,
                "cachedWarm", cachedWarm,
                "coldCacheMiss", coldMiss,
                "warmP95ImprovementRatio", warmP95Improvement));
        report.put("invalidation", invalidation);
        report.put("storage", Map.of(
                "capacity", bounded.capacity(),
                "peakEntries", bounded.peakEntries(),
                "entries", bounded.entries(),
                "evictions", bounded.evictions(),
                "bounded", storageBounded));
        report.put("decision", Map.of(
                "minimumWarmP95ImprovementRatio", MIN_WARM_P95_IMPROVEMENT,
                "persistentCacheCandidate", candidate,
                "recommendation", candidate
                        ? "candidate_requires_budget_semantics_and_production_adr_before_adoption"
                        : "retain_recomputation_without_persistent_git_context_cache"));

        Path output = Path.of(System.getProperty(
                        "nexus.git.cache.benchmark.output",
                        "target/git-context-cache-qualification.json"))
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(output.getParent());
        new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(output.toFile(), report);

        System.out.printf(
                Locale.ROOT,
                "NEXUS Git cache qualification: profile=%s repos=%d commits=%d baselineP95=%.1fus cachedP95=%.1fus improvement=%.4f candidate=%s output=%s%n",
                profile,
                repositoryCount,
                commitsPerRepository,
                baselineWarm.p95Micros(),
                cachedWarm.p95Micros(),
                warmP95Improvement,
                candidate,
                output);
    }

    private boolean qualifyHeadInvalidation() throws Exception {
        RepoFixture fixture = createRepository("invalidate-head", 6);
        PrototypeCache cache = new PrototypeCache(4);
        GitContextResult before = cache.discover(fixture.query());
        int misses = cache.misses();
        Files.writeString(fixture.target(), "class Service { int head = 2; }\n");
        commitAll(fixture.root(), "head changes target");
        GitContextResult expected = new LocalGitContextSourceProvider().discover(fixture.query());
        GitContextResult actual = cache.discover(fixture.query());
        assertEquals(expected, actual);
        assertTrue(cache.misses() > misses);
        assertNotEquals(before, actual);
        return true;
    }

    private boolean qualifyStagedInvalidation() throws Exception {
        RepoFixture fixture = createRepository("invalidate-index", 6);
        PrototypeCache cache = new PrototypeCache(4);
        cache.discover(fixture.query());
        int misses = cache.misses();
        Files.writeString(fixture.target(), "class Service { int staged = 3; }\n");
        runGit(fixture.root(), "add", "src/Service.java");
        GitContextResult expected = new LocalGitContextSourceProvider().discover(fixture.query());
        GitContextResult actual = cache.discover(fixture.query());
        assertEquals(expected, actual);
        assertTrue(cache.misses() > misses);
        assertTrue(render(actual).contains("Patch indexé"));
        return true;
    }

    private boolean qualifyWorkingTreeInvalidation() throws Exception {
        RepoFixture fixture = createRepository("invalidate-worktree", 6);
        PrototypeCache cache = new PrototypeCache(4);
        cache.discover(fixture.query());
        int misses = cache.misses();
        Files.writeString(fixture.target(), "class Service { int dirty = 4; }\n");
        GitContextResult expected = new LocalGitContextSourceProvider().discover(fixture.query());
        GitContextResult actual = cache.discover(fixture.query());
        assertEquals(expected, actual);
        assertTrue(cache.misses() > misses);
        assertTrue(render(actual).contains("Patch non indexé"));
        return true;
    }

    private boolean qualifyRenameInvalidation() throws Exception {
        RepoFixture fixture = createRepository("invalidate-rename", 6);
        PrototypeCache cache = new PrototypeCache(4);
        List<Path> renamedTarget = List.of(Path.of("src/RenamedService.java"));
        cache.discover(fixture.query(renamedTarget));
        int misses = cache.misses();
        runGit(fixture.root(), "mv", "src/Service.java", "src/RenamedService.java");
        runGit(fixture.root(), "commit", "-m", "rename target service");
        GitContextResult expected = new LocalGitContextSourceProvider().discover(fixture.query(renamedTarget));
        GitContextResult actual = cache.discover(fixture.query(renamedTarget));
        assertEquals(expected, actual);
        assertTrue(cache.misses() > misses);
        assertTrue(render(actual).contains("rename target service"));
        return true;
    }

    private boolean qualifyRebaseInvalidation() throws Exception {
        RepoFixture fixture = createRepository("invalidate-rebase", 6);
        String baseBranch = currentBranch(fixture.root());
        runGit(fixture.root(), "checkout", "-b", "benchmark-feature");
        Files.writeString(fixture.target(), "class Service { int feature = 5; }\n");
        commitAll(fixture.root(), "feature target change");
        runGit(fixture.root(), "checkout", baseBranch);
        Files.writeString(fixture.peer(), "class Peer { int base = 6; }\n");
        commitAll(fixture.root(), "base peer change");
        runGit(fixture.root(), "checkout", "benchmark-feature");

        PrototypeCache cache = new PrototypeCache(4);
        cache.discover(fixture.query());
        int misses = cache.misses();
        String beforeHead = gitOutput(fixture.root(), "rev-parse", "HEAD").trim();
        runGit(fixture.root(), "rebase", baseBranch);
        String afterHead = gitOutput(fixture.root(), "rev-parse", "HEAD").trim();
        assertNotEquals(beforeHead, afterHead);

        GitContextResult expected = new LocalGitContextSourceProvider().discover(fixture.query());
        GitContextResult actual = cache.discover(fixture.query());
        assertEquals(expected, actual);
        assertTrue(cache.misses() > misses);
        return true;
    }

    private boolean qualifyLinkedWorktreeIsolation() throws Exception {
        RepoFixture fixture = createRepository("invalidate-worktree-link", 6);
        Path linkedRoot = temporaryDirectory.resolve("linked-worktree");
        runGit(fixture.root(), "worktree", "add", "-b", "benchmark-linked", linkedRoot.toString());
        RepoFixture linked = new RepoFixture(
                linkedRoot,
                project(linkedRoot, "linked-worktree"),
                linkedRoot.resolve("src/Service.java"),
                linkedRoot.resolve("src/Peer.java"));

        PrototypeCache cache = new PrototypeCache(4);
        GitContextResult mainBefore = cache.discover(fixture.query());
        GitContextResult linkedBefore = cache.discover(linked.query());
        assertEquals(2, cache.entries());
        int hits = cache.hits();
        cache.discover(fixture.query());
        assertTrue(cache.hits() > hits);

        Files.writeString(linked.target(), "class Service { int linkedDirty = 7; }\n");
        GitContextResult expectedLinked = new LocalGitContextSourceProvider().discover(linked.query());
        GitContextResult linkedAfter = cache.discover(linked.query());
        GitContextResult mainAfter = cache.discover(fixture.query());
        assertEquals(expectedLinked, linkedAfter);
        assertNotEquals(linkedBefore, linkedAfter);
        assertEquals(mainBefore, mainAfter);
        return true;
    }

    private List<RepoFixture> createRepresentativeRepositories(int count, int commits) throws Exception {
        List<RepoFixture> fixtures = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            fixtures.add(createRepository("repo-%02d".formatted(index), commits));
        }
        return List.copyOf(fixtures);
    }

    private RepoFixture createRepository(String name, int commits) throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve(name));
        Path target = root.resolve("src/Service.java");
        Path peer = root.resolve("src/Peer.java");
        Files.createDirectories(target.getParent());
        runGit(root, "init");
        runGit(root, "config", "user.name", "NEXUS Benchmark");
        runGit(root, "config", "user.email", "nexus-benchmark@example.test");
        Files.writeString(target, "class Service { int version = 0; }\n");
        Files.writeString(peer, "class Peer { int version = 0; }\n");
        commitAll(root, "initial benchmark corpus");
        for (int commit = 1; commit < commits; commit++) {
            Files.writeString(target, "class Service { int version = " + commit + "; }\n");
            if (commit % 3 == 0) {
                Files.writeString(peer, "class Peer { int version = " + commit + "; }\n");
            }
            commitAll(root, "benchmark change " + commit);
        }
        return new RepoFixture(root, project(root, name), target, peer);
    }

    private static ProjectDescriptor project(Path root, String name) {
        return new ProjectDescriptor(
                UUID.randomUUID(),
                name,
                root,
                ProjectSourceType.LOCAL,
                Set.of("java"),
                Set.of(),
                null,
                IndexStatus.READY);
    }

    private static void commitAll(Path root, String message) throws Exception {
        runGit(root, "add", "-A");
        runGit(root, "commit", "-m", message);
    }

    private static String currentBranch(Path root) throws Exception {
        return gitOutput(root, "branch", "--show-current").trim();
    }

    private static void runGit(Path root, String... arguments) throws Exception {
        String output = gitOutput(root, arguments);
        if (output == null) {
            throw new IllegalStateException("git command returned no output state");
        }
    }

    private static String gitOutput(Path root, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("git " + String.join(" ", arguments) + " failed (" + exit + "): " + output);
        }
        return output;
    }

    private static Latency measure(
            List<RepoFixture> fixtures,
            int cycles,
            FixtureOperation operation) throws Exception {
        List<Long> nanos = new ArrayList<>(fixtures.size() * cycles);
        for (int cycle = 0; cycle < cycles; cycle++) {
            for (RepoFixture fixture : fixtures) {
                long started = System.nanoTime();
                operation.run(fixture);
                nanos.add(System.nanoTime() - started);
            }
        }
        return Latency.from(nanos);
    }

    private static double improvementRatio(double baseline, double candidate) {
        return baseline <= 0.0d ? 0.0d : (baseline - candidate) / baseline;
    }

    private static String render(GitContextResult result) {
        return result.fragments().stream()
                .map(fragment -> fragment.content())
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private static String profile() {
        String profile = System.getProperty("nexus.scale.benchmark.profile", "ci")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (!profile.equals("ci") && !profile.equals("full")) {
            throw new IllegalArgumentException("nexus.scale.benchmark.profile must be ci or full");
        }
        return profile;
    }

    @FunctionalInterface
    private interface FixtureOperation {
        void run(RepoFixture fixture) throws Exception;
    }

    private record RepoFixture(Path root, ProjectDescriptor project, Path target, Path peer) {
        GitContextQuery query() {
            return query(List.of(Path.of("src/Service.java")));
        }

        GitContextQuery query(List<Path> targets) {
            return new GitContextQuery(project, "benchmark git context", targets, true);
        }
    }

    private record Latency(int samples, double p50Micros, double p95Micros, double meanMicros) {
        static Latency from(List<Long> nanos) {
            List<Long> sorted = nanos.stream().sorted().toList();
            int p50Index = Math.max(0, (int) Math.ceil(sorted.size() * 0.50d) - 1);
            int p95Index = Math.max(0, (int) Math.ceil(sorted.size() * 0.95d) - 1);
            double mean = sorted.stream().mapToLong(Long::longValue).average().orElse(0.0d) / 1_000.0d;
            return new Latency(
                    sorted.size(),
                    sorted.get(p50Index) / 1_000.0d,
                    sorted.get(p95Index) / 1_000.0d,
                    mean);
        }
    }

    private static final class PrototypeCache {
        private final int capacity;
        private final LinkedHashMap<CacheKey, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
        private final LocalGitContextSourceProvider delegate = new LocalGitContextSourceProvider();
        private int hits;
        private int misses;
        private int evictions;
        private int peakEntries;

        private PrototypeCache(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be positive");
            }
            this.capacity = capacity;
        }

        synchronized GitContextResult discover(GitContextQuery query) throws IOException {
            CacheKey key = CacheKey.from(query);
            Fingerprint fingerprint = Fingerprint.capture(query);
            Entry existing = entries.get(key);
            if (existing != null && existing.fingerprint().equals(fingerprint)) {
                hits++;
                return existing.result();
            }

            GitContextResult result = delegate.discover(query);
            misses++;
            if (!entries.containsKey(key) && entries.size() >= capacity) {
                var iterator = entries.entrySet().iterator();
                iterator.next();
                iterator.remove();
                evictions++;
            }
            entries.put(key, new Entry(fingerprint, result));
            peakEntries = Math.max(peakEntries, entries.size());
            return result;
        }

        int capacity() {
            return capacity;
        }

        synchronized int entries() {
            return entries.size();
        }

        int hits() {
            return hits;
        }

        int misses() {
            return misses;
        }

        int evictions() {
            return evictions;
        }

        int peakEntries() {
            return peakEntries;
        }
    }

    private record Entry(Fingerprint fingerprint, GitContextResult result) {
    }

    private record CacheKey(String projectRoot, String query, List<String> targets, boolean explain) {
        static CacheKey from(GitContextQuery query) {
            return new CacheKey(
                    query.project().rootPath().toAbsolutePath().normalize().toString(),
                    query.query(),
                    query.targetPaths().stream().map(GitContextCacheQualificationBenchmarkTest::gitPath).sorted().toList(),
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
            List<String> targets = query.targetPaths().stream()
                    .map(GitContextCacheQualificationBenchmarkTest::gitPath)
                    .sorted()
                    .toList();
            try (Repository repository = new FileRepositoryBuilder()
                    .findGitDir(query.project().rootPath().toFile())
                    .setMustExist(true)
                    .build();
                 Git git = new Git(repository)) {
                ObjectId headId = repository.resolve(Constants.HEAD);
                String head = headId == null ? "unborn" : headId.name();
                String status = statusSignature(git, targets);
                String staged = diffDigest(git, targets, true);
                String unstaged = diffDigest(git, targets, false);
                return new Fingerprint(
                        repository.getWorkTree().toPath().toRealPath().toString(),
                        head,
                        status,
                        staged,
                        unstaged);
            } catch (GitAPIException exception) {
                throw new IOException("Unable to fingerprint Git repository", exception);
            }
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

        private static String diffDigest(Git git, List<String> targets, boolean cached)
                throws GitAPIException {
            DigestOutput output = new DigestOutput();
            git.diff()
                    .setCached(cached)
                    .setPathFilter(PathFilterGroup.createFromStrings(targets))
                    .setOutputStream(output)
                    .call();
            return output.hexDigest();
        }
    }

    private static final class DigestOutput extends OutputStream {
        private final MessageDigest digest;

        private DigestOutput() {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 unavailable", impossible);
            }
        }

        @Override
        public void write(int value) {
            digest.update((byte) value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
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
