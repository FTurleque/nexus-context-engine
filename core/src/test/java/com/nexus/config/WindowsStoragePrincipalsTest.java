package com.nexus.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class WindowsStoragePrincipalsTest {
    @TempDir Path directory;

    @Test
    void resolvesLocalizedNamesFromFixedSidsAndRejectsLookalikes() {
        var accounts = Map.of("S-1-5-18", "AUTORITE NT\\Système",
                "S-1-5-32-544", "BUILTIN\\Administrateurs", "S-1-3-0", "CREATEUR PROPRIETAIRE");
        Set<String> names = WindowsStoragePrincipals.resolve(accounts::get);
        accounts.values().forEach(name -> assertTrue(NexusPaths.isTrustedStoragePrincipal(name, "machine\\alice", names)));
        assertFalse(NexusPaths.isTrustedStoragePrincipal("DOMAIN\\Administrateurs", "machine\\alice", names));
        assertFalse(NexusPaths.isTrustedStoragePrincipal("DOMAIN\\alice", "machine\\alice", names));
        assertThrows(IllegalStateException.class, () -> WindowsStoragePrincipals.resolve(sid -> null));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void acceptsPrivateFixtureAclUsingRealWindowsIdentities() throws Exception {
        var lookup = directory.getFileSystem().getUserPrincipalLookupService();
        var file = Files.createFile(directory.resolve("private.txt"));
        var view = Files.getFileAttributeView(file, AclFileAttributeView.class);
        var owner = Files.getOwner(file);
        var names = WindowsStoragePrincipals.names();
        assertEquals(3, names.size(), "Les trois SID doivent être résolus sur le runner Windows");
        var entries = new java.util.ArrayList<AclEntry>();
        entries.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(Set.of(AclEntryPermission.values())).build());
        for (String name : names) {
            entries.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                    .setPrincipal(lookup.lookupPrincipalByName(name))
                    .setPermissions(AclEntryPermission.READ_DATA).build());
        }
        view.setAcl(entries);
        assertEquals(List.of(), NexusPaths.unexpectedStoragePrincipals(view.getAcl(), owner.getName()));
        assertDoesNotThrow(() -> NexusPaths.enforceAclPrivacy(file,
                NexusPaths.unexpectedStoragePrincipals(view.getAcl(), owner.getName()), true));
    }
}
