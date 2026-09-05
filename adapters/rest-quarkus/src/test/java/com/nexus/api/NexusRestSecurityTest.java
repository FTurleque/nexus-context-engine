package com.nexus.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
    void dockerLoopbackForwardRequiresRuntimeAndExplicitLoopbackDeclaration() {
        String previousRuntime = System.getProperty(NexusRestSecurity.RUNTIME_PROPERTY);
        String previousForward = System.getProperty(NexusRestSecurity.DOCKER_HOST_FORWARD_ADDRESS_PROPERTY);
        try {
            System.clearProperty(NexusRestSecurity.RUNTIME_PROPERTY);
            System.clearProperty(NexusRestSecurity.DOCKER_HOST_FORWARD_ADDRESS_PROPERTY);
            assertFalse(NexusRestSecurity.isDockerLoopbackForward("loopback-forward"));

            System.setProperty(NexusRestSecurity.RUNTIME_PROPERTY, "docker");
            assertFalse(NexusRestSecurity.isDockerLoopbackForward("loopback-forward"),
                    "Docker without an explicit host-forward declaration must fail closed");

            System.setProperty(NexusRestSecurity.DOCKER_HOST_FORWARD_ADDRESS_PROPERTY, "127.0.0.1");
            assertTrue(NexusRestSecurity.isDockerLoopbackForward("loopback-forward"));

            System.setProperty(NexusRestSecurity.DOCKER_HOST_FORWARD_ADDRESS_PROPERTY, "localhost");
            assertTrue(NexusRestSecurity.isDockerLoopbackForward("loopback-forward"));

            System.setProperty(NexusRestSecurity.DOCKER_HOST_FORWARD_ADDRESS_PROPERTY, "0.0.0.0");
            assertFalse(NexusRestSecurity.isDockerLoopbackForward("loopback-forward"));

            System.setProperty(NexusRestSecurity.DOCKER_HOST_FORWARD_ADDRESS_PROPERTY, "127.0.0.1");
            System.setProperty(NexusRestSecurity.RUNTIME_PROPERTY, "native");
            assertFalse(NexusRestSecurity.isDockerLoopbackForward("loopback-forward"));
            assertFalse(NexusRestSecurity.isDockerLoopbackForward("direct-https"));
        } finally {
            restoreProperty(NexusRestSecurity.RUNTIME_PROPERTY, previousRuntime);
            restoreProperty(NexusRestSecurity.DOCKER_HOST_FORWARD_ADDRESS_PROPERTY, previousForward);
        }
    }

    @Test
    void keepsRemoteHttpsModesExplicit() {
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
            restoreProperty(NexusRestProjectRootPolicy.ROOTS_PROPERTY, previous);
        }
    }

    @Test
    void persistedProjectAuthorizationPreservesLocalModeButFailsClosedWithAllowlist() throws Exception {
        Path allowed = Files.createDirectories(tempDir.resolve("persisted-allowed"));
        Path child = Files.createDirectories(allowed.resolve("project"));
        Path outside = Files.createDirectories(tempDir.resolve("persisted-outside"));
        Path missing = tempDir.resolve("temporarily-missing-project");
        String previous = System.getProperty(NexusRestProjectRootPolicy.ROOTS_PROPERTY);
        try {
            System.clearProperty(NexusRestProjectRootPolicy.ROOTS_PROPERTY);
            assertDoesNotThrow(() -> NexusRestProjectRootPolicy.requireAllowedPersisted(missing));
            assertTrue(NexusRestProjectRootPolicy.isAllowedPersisted(missing));

            System.setProperty(NexusRestProjectRootPolicy.ROOTS_PROPERTY, allowed.toString());
            assertDoesNotThrow(() -> NexusRestProjectRootPolicy.requireAllowedPersisted(child));
            assertThrows(IllegalArgumentException.class,
                    () -> NexusRestProjectRootPolicy.requireAllowedPersisted(outside));
            assertFalse(NexusRestProjectRootPolicy.isAllowedPersisted(missing));
        } finally {
            restoreProperty(NexusRestProjectRootPolicy.ROOTS_PROPERTY, previous);
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
