package com.nexus.security;

import com.nexus.config.NexusPaths;
import com.nexus.config.SecurityPolicy;
import com.nexus.testsupport.SymbolicLinks;
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

class PrivateStoragePolicyTest {
    private static final Set<String> ENGLISH_SYSTEM_NAMES = Set.of(
            "NT AUTHORITY\\SYSTEM", "BUILTIN\\ADMINISTRATORS", "CREATOR OWNER");

    private static boolean trusted(String name, String user) {
        return PrivateStoragePolicy.isTrustedStoragePrincipal(name, user, ENGLISH_SYSTEM_NAMES);
    }

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsNestedPrivateDirectoryInsideHome() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("home"));
        Path index = paths.projectLuceneIndex(UUID.randomUUID());

        PrivateStoragePolicy.ensurePrivateDirectory(paths, index);

        assertTrue(Files.isDirectory(index));
    }

    @Test
    void rejectsPathOutsideHome() {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("home"));

        assertThrows(IOException.class,
                () -> PrivateStoragePolicy.ensurePrivateDirectory(paths, temporaryDirectory.resolve("outside")));
    }

    @Test
    void rejectsSymlinkInNestedPersistentPath() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("home"));
        PrivateStoragePolicy.ensurePrivateStorage(paths);
        UUID projectId = UUID.randomUUID();
        Path projectDirectory = paths.indexesDirectory().resolve(projectId.toString());
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
        SymbolicLinks.createOrSkip(projectDirectory, outside);

        assertThrows(IOException.class,
                () -> PrivateStoragePolicy.ensurePrivateDirectory(paths, paths.projectLuceneIndex(projectId)));
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

        List<String> unexpected = PrivateStoragePolicy.unexpectedStoragePrincipals(
                List.of(currentUser, system, everyoneRead, everyoneWrite, harmless, deny),
                "WORKSTATION\\alice", ENGLISH_SYSTEM_NAMES);

        assertEquals(List.of("Everyone"), unexpected);
    }

    @Test
    void privateStorageRequirementIsStrictAndPropertyOverridesEnvironment() {
        String previous = System.getProperty(NexusPaths.REQUIRE_PRIVATE_STORAGE_PROPERTY);
        try {
            System.setProperty(NexusPaths.REQUIRE_PRIVATE_STORAGE_PROPERTY, "true");
            assertTrue(SecurityPolicy.requirePrivateStorage());

            System.setProperty(NexusPaths.REQUIRE_PRIVATE_STORAGE_PROPERTY, "false");
            assertFalse(SecurityPolicy.requirePrivateStorage());

            System.setProperty(NexusPaths.REQUIRE_PRIVATE_STORAGE_PROPERTY, "yes");
            assertThrows(IllegalStateException.class, SecurityPolicy::requirePrivateStorage);
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
                () -> PrivateStoragePolicy.enforceAclPrivacy(
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
