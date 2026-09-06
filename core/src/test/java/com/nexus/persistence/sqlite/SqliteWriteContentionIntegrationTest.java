package com.nexus.persistence.sqlite;

import com.nexus.config.NexusPaths;
import com.nexus.index.AnalysisResult;
import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.CodeSymbol;
import com.nexus.index.FileCategory;
import com.nexus.index.IndexedFileUpdate;
import com.nexus.index.IndexedSymbol;
import com.nexus.index.ProjectIndexLockManager;
import com.nexus.index.ScannedFile;
import com.nexus.index.SymbolKind;
import com.nexus.persistence.PersistenceException;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteWriteContentionIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void projectRegistrationRecoversFromShortRealCrossProjectContentionAndDuplicateRootIsIdempotentWithoutRetry()
            throws Exception {
        AtomicReference<CountDownLatch> releaseOnRetry = new AtomicReference<>();
        SqliteWriteRetryPolicy policy = releasingPolicy(releaseOnRetry);
        NexusPaths paths = new NexusPaths(tempDir.resolve("project-save"));
        SqliteDatabase database = new SqliteDatabase(paths, 30, policy);
        SqliteProjectRepository projects = new SqliteProjectRepository(database);
        ProjectDescriptor projectA = project("project-a", tempDir.resolve("project-a"));
        ProjectDescriptor projectB = project("project-b", tempDir.resolve("project-b"));
        projects.save(projectA);

        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        releaseOnRetry.set(release);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = holdWriter(database, projectA.id(), lockHeld, release, executor);
            assertTrue(lockHeld.await(1, TimeUnit.SECONDS));

            projects.save(projectB);
            holder.get(1, TimeUnit.SECONDS);

            assertEquals(projectB.id(), projects.findById(projectB.id()).orElseThrow().id());
            assertEquals(1L, database.writeRetryCount(),
                    "La contention courte doit provoquer exactement un retry de transaction complète");

            long retriesBeforeDuplicateRoot = database.writeRetryCount();
            ProjectDescriptor duplicateRoot = project("duplicate-root", projectB.rootPath());
            ProjectDescriptor persisted = projects.save(duplicateRoot);

            assertEquals(projectB.id(), persisted.id(),
                    "Un nouvel UUID sur un root_path déjà enregistré doit retourner le projet persistant");
            assertEquals(projectB.name(), persisted.name());
            assertEquals(projectB.rootPath(), persisted.rootPath());
            assertTrue(projects.findById(duplicateRoot.id()).isEmpty(),
                    "Le candidat perdant ne doit créer aucune seconde ligne projet");
            assertEquals(retriesBeforeDuplicateRoot, database.writeRetryCount(),
                    "Le conflit root_path idempotent ne doit pas consommer un retry de contention");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void conflictingExistingProjectMoveStillFailsWithoutContentionRetry() {
        NexusPaths paths = new NexusPaths(tempDir.resolve("project-constraint"));
        SqliteDatabase database = new SqliteDatabase(paths);
        SqliteProjectRepository projects = new SqliteProjectRepository(database);
        ProjectDescriptor projectA = project("project-a", tempDir.resolve("constraint-a"));
        ProjectDescriptor projectB = project("project-b", tempDir.resolve("constraint-b"));
        projects.save(projectA);
        projects.save(projectB);

        long retriesBeforeConstraint = database.writeRetryCount();
        ProjectDescriptor conflictingMove = new ProjectDescriptor(
                projectA.id(),
                projectA.name(),
                projectB.rootPath(),
                projectA.sourceType(),
                projectA.languages(),
                projectA.technologies(),
                projectA.lastIndexedAt(),
                projectA.indexStatus());

        PersistenceException constraint = assertThrows(
                PersistenceException.class,
                () -> projects.save(conflictingMove));
        SQLiteException sqliteConstraint = sqliteFailure(constraint);
        assertNotNull(sqliteConstraint);
        assertTrue(sqliteConstraint.getResultCode() != SQLiteErrorCode.SQLITE_BUSY
                && sqliteConstraint.getResultCode() != SQLiteErrorCode.SQLITE_LOCKED);
        assertEquals(retriesBeforeConstraint, database.writeRetryCount(),
                "Une vraie violation de contrainte ne doit jamais consommer un retry de contention");
        assertEquals(projectA.rootPath(), projects.findById(projectA.id()).orElseThrow().rootPath());
        assertEquals(projectB.rootPath(), projects.findById(projectB.id()).orElseThrow().rootPath());
    }

    @Test
    void indexAndExternalWritesRetryAtomicallyWithoutDuplicateGenerationOrNoOpInvalidation()
            throws Exception {
        AtomicReference<CountDownLatch> releaseOnRetry = new AtomicReference<>();
        SqliteWriteRetryPolicy policy = releasingPolicy(releaseOnRetry);
        NexusPaths paths = new NexusPaths(tempDir.resolve("index-writes"));
        SqliteDatabase database = new SqliteDatabase(paths, 30, policy);
        SqliteProjectRepository projects = new SqliteProjectRepository(database);
        SqliteIndexRepository index = new SqliteIndexRepository(database);
        ProjectDescriptor projectA = project("index-a", tempDir.resolve("index-a"));
        ProjectDescriptor projectB = project("index-b", tempDir.resolve("index-b"));
        projects.save(projectA);
        projects.save(projectB);
        IndexedFileUpdate update = indexedFile(projectB, "src/Main.java");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch firstLockHeld = new CountDownLatch(1);
            CountDownLatch firstRelease = new CountDownLatch(1);
            releaseOnRetry.set(firstRelease);
            Future<?> firstHolder = holdWriter(database, projectA.id(), firstLockHeld, firstRelease, executor);
            assertTrue(firstLockHeld.await(1, TimeUnit.SECONDS));

            index.applyChanges(projectB.id(), List.of(update), Set.of());
            firstHolder.get(1, TimeUnit.SECONDS);

            assertEquals(1L, index.generation(projectB.id()),
                    "Le retry de applyChanges ne doit incrémenter la génération qu'une fois");
            assertEquals(1, index.findFiles(projectB.id()).size(),
                    "Le retry ne doit pas dupliquer la ligne de fichier");
            assertEquals(1L, database.writeRetryCount());

            CodeIntelligenceSnapshot snapshot = new CodeIntelligenceSnapshot(
                    "scip",
                    List.of(new IndexedSymbol(
                            "src/Main.java",
                            new CodeSymbol(
                                    SymbolKind.TYPE,
                                    "Main",
                                    "scip:demo/Main#",
                                    "Main",
                                    1,
                                    1,
                                    "scip"))),
                    List.of());

            CountDownLatch secondLockHeld = new CountDownLatch(1);
            CountDownLatch secondRelease = new CountDownLatch(1);
            releaseOnRetry.set(secondRelease);
            Future<?> secondHolder = holdWriter(database, projectA.id(), secondLockHeld, secondRelease, executor);
            assertTrue(secondLockHeld.await(1, TimeUnit.SECONDS));

            index.replaceExternalCodeIntelligence(projectB.id(), snapshot);
            secondHolder.get(1, TimeUnit.SECONDS);

            assertEquals(2L, index.generation(projectB.id()),
                    "Le refresh externe retryé doit incrémenter la génération exactement une fois");
            assertEquals(Set.of("scip"), index.findExternalProviders(projectB.id()));
            assertEquals(1, index.findSymbols(projectB.id()).stream()
                    .filter(symbol -> "scip".equals(symbol.symbol().sourceProvider()))
                    .count());
            assertEquals(2L, database.writeRetryCount());

            releaseOnRetry.set(null);
            long retriesBeforeNoOp = database.writeRetryCount();
            index.replaceExternalCodeIntelligence(projectB.id(), snapshot);
            assertEquals(2L, index.generation(projectB.id()),
                    "Un refresh externe identique reste un vrai no-op");
            assertEquals(retriesBeforeNoOp, database.writeRetryCount());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void persistentContentionFailsWithinPolicyWhileConcurrentReadStillWorksAndNoPartialRowsRemain()
            throws Exception {
        SqliteWriteRetryPolicy policy = new SqliteWriteRetryPolicy(
                2,
                Duration.ZERO,
                Duration.ZERO,
                ignored -> { });
        NexusPaths paths = new NexusPaths(tempDir.resolve("bounded-failure"));
        SqliteDatabase database = new SqliteDatabase(paths, 20, policy);
        SqliteProjectRepository projects = new SqliteProjectRepository(database);
        SqliteIndexRepository index = new SqliteIndexRepository(database);
        ProjectDescriptor projectA = project("bounded-a", tempDir.resolve("bounded-a"));
        ProjectDescriptor projectB = project("bounded-b", tempDir.resolve("bounded-b"));
        projects.save(projectA);
        projects.save(projectB);
        IndexedFileUpdate update = indexedFile(projectB, "src/Bounded.java");

        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = holdWriter(database, projectA.id(), lockHeld, release, executor);
            assertTrue(lockHeld.await(1, TimeUnit.SECONDS));

            assertEquals(projectB.id(), projects.findById(projectB.id()).orElseThrow().id(),
                    "Une lecture concurrente doit rester disponible pendant le writer");

            UUID projectBId = projectB.id();
            List<IndexedFileUpdate> updates = List.of(update);
            Set<String> removedPaths = Set.of();
            long started = System.nanoTime();
            PersistenceException failure = assertThrows(
                    PersistenceException.class,
                    () -> index.applyChanges(projectBId, updates, removedPaths));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            SQLiteException sqliteFailure = sqliteFailure(failure);
            assertNotNull(sqliteFailure);
            assertTrue(sqliteFailure.getResultCode() == SQLiteErrorCode.SQLITE_BUSY
                    || sqliteFailure.getResultCode() == SQLiteErrorCode.SQLITE_LOCKED);
            assertEquals(1L, database.writeRetryCount(),
                    "Deux tentatives impliquent exactement un retry");
            assertTrue(elapsedMillis < 1_000L,
                    () -> "La politique de test doit échouer de façon bornée, elapsed=" + elapsedMillis + " ms");
            assertEquals(0L, index.generation(projectB.id()));
            assertTrue(index.findFiles(projectB.id()).isEmpty(),
                    "Aucune ligne partielle ne doit survivre à l'échec borné");

            release.countDown();
            holder.get(1, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentBootstrapAndDistinctProjectFileLocksRemainCompatible() throws Exception {
        NexusPaths bootstrapPaths = new NexusPaths(tempDir.resolve("bootstrap"));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SqliteDatabase> first = executor.submit(() -> {
                start.await();
                return new SqliteDatabase(bootstrapPaths);
            });
            Future<SqliteDatabase> second = executor.submit(() -> {
                start.await();
                return new SqliteDatabase(bootstrapPaths);
            });
            start.countDown();
            SqliteDatabase database = first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);

            try (Connection connection = database.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT COUNT(*) FROM schema_migrations");
                 ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals(5L, resultSet.getLong(1));
            }
        } finally {
            executor.shutdownNow();
        }

        NexusPaths lockPaths = new NexusPaths(tempDir.resolve("file-locks"));
        ProjectIndexLockManager firstManager = ProjectIndexLockManager.fileBacked(lockPaths);
        ProjectIndexLockManager secondManager = ProjectIndexLockManager.fileBacked(lockPaths);
        try (ProjectIndexLockManager.LockHandle first = firstManager.acquire(UUID.randomUUID());
             ProjectIndexLockManager.LockHandle second = secondManager.acquire(UUID.randomUUID())) {
            assertNotNull(first);
            assertNotNull(second);
        }
    }

    private SqliteWriteRetryPolicy releasingPolicy(AtomicReference<CountDownLatch> releaseOnRetry) {
        return new SqliteWriteRetryPolicy(
                2,
                Duration.ofMillis(2),
                Duration.ofMillis(2),
                millis -> {
                    CountDownLatch release = releaseOnRetry.get();
                    if (release != null) {
                        release.countDown();
                    }
                    Thread.sleep(millis);
                });
    }

    private Future<?> holdWriter(
            SqliteDatabase database,
            UUID projectId,
            CountDownLatch lockHeld,
            CountDownLatch release,
            ExecutorService executor) {
        return executor.submit(() -> {
            try (Connection connection = database.openConnection()) {
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE projects SET name = name || '-locked' WHERE id = ?")) {
                    statement.setString(1, projectId.toString());
                    statement.executeUpdate();
                }
                lockHeld.countDown();
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release SQLite writer lock");
                }
                connection.rollback();
            }
            return null;
        });
    }

    private ProjectDescriptor project(String name, Path root) {
        return new ProjectDescriptor(
                UUID.randomUUID(),
                name,
                root.toAbsolutePath().normalize(),
                ProjectSourceType.LOCAL,
                Set.of(),
                Set.of(),
                null,
                IndexStatus.NOT_INDEXED);
    }

    private IndexedFileUpdate indexedFile(ProjectDescriptor project, String relativePath) {
        Path absolute = project.rootPath().resolve(relativePath).toAbsolutePath().normalize();
        ScannedFile file = new ScannedFile(
                absolute,
                relativePath,
                "java",
                16,
                "hash-" + relativePath,
                Instant.parse("2026-08-09T00:00:00Z"),
                4,
                FileCategory.SOURCE);
        return new IndexedFileUpdate(
                file,
                new AnalysisResult(absolute, "java", List.of(), List.of()));
    }

    private static SQLiteException sqliteFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLiteException sqliteException) {
                return sqliteException;
            }
            current = current.getCause();
        }
        return null;
    }
}
