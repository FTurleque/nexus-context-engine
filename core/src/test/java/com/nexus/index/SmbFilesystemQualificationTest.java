package com.nexus.index;

import com.nexus.config.NexusPaths;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SmbFilesystemQualificationTest {

    private static final String SMB_ROOT_PROPERTY = "nexus.smb.root";
    private static final String REQUIRED_PROPERTY = "nexus.smb.qualification.required";

    @Test
    void qualifiesBasicIoThroughARealUncShare() throws Exception {
        Path root = qualificationRoot();
        Path fixture = Files.createDirectories(root.resolve("basic-io-" + UUID.randomUUID()));
        Path source = fixture.resolve("source.txt");
        Path target = fixture.resolve("target.txt");

        try {
            Files.writeString(source, "nexus-smb-round-trip", StandardCharsets.UTF_8);
            assertEquals("nexus-smb-round-trip", Files.readString(source, StandardCharsets.UTF_8));
            Files.move(source, target);
            assertEquals("nexus-smb-round-trip", Files.readString(target, StandardCharsets.UTF_8));

            System.out.printf(
                    "NEXUS SMB qualification: os=%s root=%s store=%s type=%s%n",
                    System.getProperty("os.name"),
                    root,
                    Files.getFileStore(root).name(),
                    Files.getFileStore(root).type());
        } finally {
            Files.deleteIfExists(target);
            Files.deleteIfExists(source);
            Files.deleteIfExists(fixture);
        }
    }

    @Test
    void qualifiesProjectFileLockAcrossDistinctJvmProcessesOverSmb() throws Exception {
        Path root = qualificationRoot();
        NexusPaths paths = new NexusPaths(root.resolve("nexus-home-" + UUID.randomUUID()));
        ProjectIndexLockManager manager = ProjectIndexLockManager.fileBacked(paths);
        UUID projectId = UUID.randomUUID();

        ProbeResult blocked;
        try (ProjectIndexLockManager.LockHandle ignored = manager.acquire(projectId)) {
            blocked = runProbe(paths, projectId);
        }
        assertEquals(ProjectIndexLockProbe.EXIT_BUSY, blocked.exitCode(), blocked.output());

        ProbeResult acquiredAfterRelease = runProbe(paths, projectId);
        assertEquals(ProjectIndexLockProbe.EXIT_ACQUIRED, acquiredAfterRelease.exitCode(), acquiredAfterRelease.output());
        assertTrue(Files.isRegularFile(paths.projectIndexLock(projectId)));
    }

    private static Path qualificationRoot() {
        String configured = System.getProperty(SMB_ROOT_PROPERTY);
        boolean required = Boolean.parseBoolean(System.getProperty(REQUIRED_PROPERTY, "false"));
        if (configured == null || configured.isBlank()) {
            if (required) {
                fail("La qualification SMB est obligatoire mais -D" + SMB_ROOT_PROPERTY + " est absent");
            }
            Assumptions.abort("Qualification SMB opt-in non activee");
        }

        Path root = Path.of(configured).toAbsolutePath().normalize();
        if (required) {
            assertTrue(isWindows(), "La qualification SMB loopback actuelle doit s'executer sur Windows");
            assertTrue(root.toString().startsWith("\\\\"), "Le fixture SMB doit etre un chemin UNC reel");
            assertTrue(Files.isDirectory(root), "Le partage SMB de qualification doit etre accessible");
        }
        return root;
    }

    private static ProbeResult runProbe(NexusPaths paths, UUID projectId) throws Exception {
        Process process = new ProcessBuilder(
                javaExecutable().toString(),
                "-cp",
                testClasspath(),
                ProjectIndexLockProbe.class.getName(),
                paths.home().toString(),
                projectId.toString())
                .redirectErrorStream(true)
                .start();

        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            fail("Le probe FileLock SMB inter-JVM n'a pas termine dans le delai imparti");
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProbeResult(process.exitValue(), output);
    }

    private static Path javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String testClasspath() {
        String surefireClasspath = System.getProperty("surefire.test.class.path");
        if (surefireClasspath != null && !surefireClasspath.isBlank()) {
            return surefireClasspath;
        }
        return System.getProperty("java.class.path");
    }

    private record ProbeResult(int exitCode, String output) {
    }
}
