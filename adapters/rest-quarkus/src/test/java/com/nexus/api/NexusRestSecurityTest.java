package com.nexus.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NexusRestSecurityTest {

    @TempDir
    Path tempDir;

    @Test
    void recognizesLoopbackWithoutTreatingWildcardBindingsAsLocal() {
        assertTrue(NexusRestSecurity.isLoopbackHost("127.0.0.1"));
        assertTrue(NexusRestSecurity.isLoopbackHost("localhost"));
        assertTrue(NexusRestSecurity.isLoopbackHost("::1"));
        assertFalse(NexusRestSecurity.isLoopbackHost("0.0.0.0"));
    }

    @Test
    void validatesBearerTokensExactly() {
        assertTrue(NexusRestSecurity.tokenMatches("secret-value", "Bearer secret-value"));
        assertTrue(NexusRestSecurity.tokenMatches("secret-value", "bearer secret-value"));
        assertFalse(NexusRestSecurity.tokenMatches("secret-value", null));
        assertFalse(NexusRestSecurity.tokenMatches("secret-value", "secret-value"));
        assertFalse(NexusRestSecurity.tokenMatches("secret-value", "Bearer wrong"));
    }

    @Test
    void requiresLengthAndEntropyForRemoteTokens() {
        assertFalse(NexusRestSecurity.isStrongRemoteToken("short"));
        assertFalse(NexusRestSecurity.isStrongRemoteToken("a".repeat(64)));
        assertFalse(NexusRestSecurity.isStrongRemoteToken("ab".repeat(32)));
        assertTrue(NexusRestSecurity.isStrongRemoteToken("0123456789abcdef0123456789abcdef"));
        assertTrue(NexusRestSecurity.isStrongRemoteToken(
                "6df1462d571a6925e3bc3934ee10c6c55a965116fb47e2bc4db77ac7a5d69d34"));
    }

    @Test
    void requiresHttpsCapableModesForNonLoopbackAdministration() {
        assertTrue(NexusRestSecurity.isSupportedRemoteExposureMode("loopback-forward"));
        assertTrue(NexusRestSecurity.isSupportedRemoteExposureMode("reverse-proxy-https"));
        assertTrue(NexusRestSecurity.isSupportedRemoteExposureMode("direct-https"));
        assertFalse(NexusRestSecurity.isSupportedRemoteExposureMode("plain-http"));

        assertFalse(NexusRestSecurity.isSecureNonLoopbackExposureMode("loopback-forward"));
        assertTrue(NexusRestSecurity.isSecureNonLoopbackExposureMode("reverse-proxy-https"));
        assertTrue(NexusRestSecurity.isSecureNonLoopbackExposureMode("direct-https"));
        assertFalse(NexusRestSecurity.isSecureNonLoopbackExposureMode("plain-http"));
    }

    @Test
    void canonicalProjectRootAllowlistBlocksPathsOutsideConfiguredRoots() throws Exception {
        Path allowed = Files.createDirectories(tempDir.resolve("allowed"));
        Path child = Files.createDirectories(allowed.resolve("nested"));
        Path outside = Files.createDirectories(tempDir.resolve("outside-" + UUID.randomUUID()));
        String previous = System.getProperty(NexusRestProjectRootPolicy.ROOTS_PROPERTY);
        try {
            System.setProperty(NexusRestProjectRootPolicy.ROOTS_PROPERTY, allowed.toString());
            assertEquals(child.toRealPath(), NexusRestProjectRootPolicy.requireAllowed(child));
            assertThrows(IllegalArgumentException.class,
                    () -> NexusRestProjectRootPolicy.requireAllowed(outside));
        } finally {
            if (previous == null) {
                System.clearProperty(NexusRestProjectRootPolicy.ROOTS_PROPERTY);
            } else {
                System.setProperty(NexusRestProjectRootPolicy.ROOTS_PROPERTY, previous);
            }
        }
    }
}
