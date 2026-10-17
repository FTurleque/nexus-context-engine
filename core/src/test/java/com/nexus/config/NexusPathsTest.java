package com.nexus.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NexusPathsTest {
    private static final Set<String> ENGLISH_SYSTEM_NAMES = Set.of(
            "NT AUTHORITY\\SYSTEM", "BUILTIN\\ADMINISTRATORS", "CREATOR OWNER");
    private static boolean trusted(String name, String user) {
        return NexusPaths.isTrustedStoragePrincipal(name, user, ENGLISH_SYSTEM_NAMES);
    }


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
    void classifiesOnlyExactExpectedWindowsAclPrincipals() {
        assertTrue(trusted("WORKSTATION\\alice", "WORKSTATION\\alice"));
        assertTrue(trusted("NT AUTHORITY\\SYSTEM", "WORKSTATION\\alice"));
        assertTrue(trusted("BUILTIN\\Administrators", "WORKSTATION\\alice"));
        assertTrue(trusted("CREATOR OWNER", "WORKSTATION\\alice"));

        assertFalse(trusted("DOMAIN\\alice", "WORKSTATION\\alice"));
        assertFalse(trusted("CONTOSO\\Administrators", "WORKSTATION\\alice"));
        assertFalse(trusted("Everyone", "WORKSTATION\\alice"));
        assertFalse(trusted(
                "NT AUTHORITY\\Authenticated Users", "WORKSTATION\\alice"));
        assertFalse(trusted("BUILTIN\\Users", "WORKSTATION\\alice"));
    }

    @Test
    void identifiesOnlySensitiveUnexpectedAclPrincipalsAndDeduplicatesThem() {
        AclEntry currentUser = allow("WORKSTATION\\alice", AclEntryPermission.READ_DATA);
        AclEntry system = allow("NT AUTHORITY\\SYSTEM", AclEntryPermission.WRITE_DATA);
        AclEntry everyoneRead = allow("Everyone", AclEntryPermission.READ_DATA);
        AclEntry everyoneWrite = allow("Everyone", AclEntryPermission.WRITE_DATA);
        AclEntry harmless = allow("BUILTIN\\Users", AclEntryPermission.READ_ATTRIBUTES);
        AclEntry deny = AclEntry.newBuilder()
                .setType(AclEntryType.DENY)
                .setPrincipal(() -> "Guests")
                .setPermissions(AclEntryPermission.READ_DATA)
                .build();

        List<String> unexpected = NexusPaths.unexpectedStoragePrincipals(
                List.of(currentUser, system, everyoneRead, everyoneWrite, harmless, deny),
                "WORKSTATION\\alice", ENGLISH_SYSTEM_NAMES);

        assertEquals(List.of("Everyone"), unexpected);
    }

    @Test
    void privateStorageRequirementIsStrictAndPropertyOverridesEnvironment() {
        String previous = System.getProperty(NexusPaths.REQUIRE_PRIVATE_STORAGE_PROPERTY);
        try {
            System.setProperty(NexusPaths.REQUIRE_PRIVATE_STORAGE_PROPERTY, "true");
            assertTrue(NexusPaths.requirePrivateStorage());

            System.setProperty(NexusPaths.REQUIRE_PRIVATE_STORAGE_PROPERTY, "false");
            assertFalse(NexusPaths.requirePrivateStorage());

            System.setProperty(NexusPaths.REQUIRE_PRIVATE_STORAGE_PROPERTY, "yes");
            assertThrows(IllegalStateException.class, NexusPaths::requirePrivateStorage);
        } finally {
            if (previous == null) {
                System.clearProperty(NexusPaths.REQUIRE_PRIVATE_STORAGE_PROPERTY);
            } else {
                System.setProperty(NexusPaths.REQUIRE_PRIVATE_STORAGE_PROPERTY, previous);
            }
        }
    }

    @Test
    void strictPrivateStoragePolicyFailsClosedOnUnexpectedPrincipal() {
        IOException failure = assertThrows(
                IOException.class,
                () -> NexusPaths.enforceAclPrivacy(
                        Path.of("nexus-home"),
                        List.of("Everyone"),
                        true));

        assertTrue(failure.getMessage().contains(NexusPaths.REQUIRE_PRIVATE_STORAGE_ENVIRONMENT_VARIABLE));
        assertTrue(failure.getMessage().contains("Everyone"));
    }

    private static AclEntry allow(String principal, AclEntryPermission permission) {
        return AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(() -> principal)
                .setPermissions(permission)
                .build();
    }
}
