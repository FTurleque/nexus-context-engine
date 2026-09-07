package com.nexus.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NexusPathsTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsNestedPrivateDirectoryInsideHome() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("home"));
        Path index = paths.projectLuceneIndex(UUID.randomUUID());

        paths.ensurePrivateDirectory(index);

        assertTrue(Files.isDirectory(index));
    }

    @Test
    void rejectsSymlinkInNestedPersistentPath() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("home"));
        paths.ensurePrivateStorage();
        UUID projectId = UUID.randomUUID();
        Path projectDirectory = paths.indexesDirectory().resolve(projectId.toString());
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
        try {
            Files.createSymbolicLink(projectDirectory, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(false, "symbolic links unavailable: " + exception.getMessage());
        }

        assertThrows(IOException.class, () -> paths.ensurePrivateDirectory(paths.projectLuceneIndex(projectId)));
    }

    @Test
    void classifiesExpectedAndBroadWindowsAclPrincipals() {
        assertTrue(NexusPaths.isTrustedStoragePrincipal("WORKSTATION\\alice", "alice"));
        assertTrue(NexusPaths.isTrustedStoragePrincipal("NT AUTHORITY\\SYSTEM", "alice"));
        assertTrue(NexusPaths.isTrustedStoragePrincipal("BUILTIN\\Administrators", "alice"));
        assertTrue(NexusPaths.isTrustedStoragePrincipal("CREATOR OWNER", "alice"));

        assertFalse(NexusPaths.isTrustedStoragePrincipal("Everyone", "alice"));
        assertFalse(NexusPaths.isTrustedStoragePrincipal("NT AUTHORITY\\Authenticated Users", "alice"));
        assertFalse(NexusPaths.isTrustedStoragePrincipal("BUILTIN\\Users", "alice"));
    }
}
