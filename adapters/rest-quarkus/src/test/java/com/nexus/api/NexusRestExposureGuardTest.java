package com.nexus.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NexusRestExposureGuardTest {

    private static final String STRONG_TOKEN =
            "6df1462d571a6925e3bc3934ee10c6c55a965116fb47e2bc4db77ac7a5d69d34";

    private final Map<String, String> previousProperties = new LinkedHashMap<>();

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void configureRemotePrerequisites() throws Exception {
        rememberAndSet(NexusRestSecurity.LOCAL_HARDENING_PROPERTY, "false");
        rememberAndSet(NexusRestSecurity.TOKEN_PROPERTY, STRONG_TOKEN);
        rememberAndSet(
                NexusRestProjectRootPolicy.ROOTS_PROPERTY,
                Files.createDirectories(temporaryDirectory.resolve("workspace")).toString());
    }

    @AfterEach
    void restoreProperties() {
        previousProperties.forEach((name, value) -> {
            if (value == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, value);
            }
        });
    }

    @Test
    void keepsHistoricalLoopbackPostureWhenLocalHardeningIsDisabled() {
        rememberAndClear(NexusRestSecurity.TOKEN_PROPERTY);
        rememberAndClear(NexusRestProjectRootPolicy.ROOTS_PROPERTY);
        assertDoesNotThrow(this::validateLoopbackRestHost);
    }

    @Test
    void hardenedLoopbackAcceptsStrongTokenAndProjectRootAllowlist() {
        rememberAndSet(NexusRestSecurity.LOCAL_HARDENING_PROPERTY, "true");
        assertDoesNotThrow(this::validateLoopbackRestHost);
    }

    @Test
    void hardenedLoopbackRejectsMissingOrWeakToken() {
        rememberAndSet(NexusRestSecurity.LOCAL_HARDENING_PROPERTY, "true");
        rememberAndClear(NexusRestSecurity.TOKEN_PROPERTY);
        IllegalStateException missing = assertThrows(IllegalStateException.class, this::validateLoopbackRestHost);
        assertTrue(missing.getMessage().contains(NexusRestSecurity.TOKEN_ENVIRONMENT_VARIABLE));

        rememberAndSet(NexusRestSecurity.TOKEN_PROPERTY, "weak-token");
        IllegalStateException weak = assertThrows(IllegalStateException.class, this::validateLoopbackRestHost);
        assertTrue(weak.getMessage().contains(NexusRestSecurity.TOKEN_ENVIRONMENT_VARIABLE));
    }

    @Test
    void hardenedLoopbackRejectsMissingProjectRootAllowlist() {
        rememberAndSet(NexusRestSecurity.LOCAL_HARDENING_PROPERTY, "true");
        rememberAndClear(NexusRestProjectRootPolicy.ROOTS_PROPERTY);
        IllegalStateException error = assertThrows(IllegalStateException.class, this::validateLoopbackRestHost);
        assertTrue(error.getMessage().contains(NexusRestProjectRootPolicy.ROOTS_ENVIRONMENT_VARIABLE));
    }

    @Test
    void hardenedLoopbackRejectsInvalidBooleanConfiguration() {
        rememberAndSet(NexusRestSecurity.LOCAL_HARDENING_PROPERTY, "sometimes");
        IllegalStateException error = assertThrows(IllegalStateException.class, this::validateLoopbackRestHost);
        assertTrue(error.getMessage().contains(NexusRestSecurity.LOCAL_HARDENING_ENVIRONMENT_VARIABLE));
    }

    @Test
    void acceptsDockerLoopbackForwardDeclaredAsIpv4Loopback() {
        configureDockerLoopbackForward("127.0.0.1");
        assertDoesNotThrow(this::validateWildcardRestHost);
    }

    @Test
    void acceptsDockerLoopbackForwardDeclaredAsLocalhost() {
        configureDockerLoopbackForward("localhost");
        assertDoesNotThrow(this::validateWildcardRestHost);
    }

    @Test
    void rejectsDockerLoopbackForwardDeclaredAsWildcard() {
        configureDockerLoopbackForward("0.0.0.0");
        IllegalStateException error = assertThrows(IllegalStateException.class, this::validateWildcardRestHost);
        assertTrue(error.getMessage().contains(NexusRestSecurity.DOCKER_HOST_FORWARD_ADDRESS_ENVIRONMENT_VARIABLE));
    }

    @Test
    void rejectsDockerLoopbackForwardWithoutReliableDeclaration() {
        rememberAndSet(NexusRestSecurity.RUNTIME_PROPERTY, "docker");
        rememberAndSet(NexusRestSecurity.EXPOSURE_MODE_PROPERTY, "loopback-forward");
        rememberAndClear(NexusRestSecurity.DOCKER_HOST_FORWARD_ADDRESS_PROPERTY);

        IllegalStateException error = assertThrows(IllegalStateException.class, this::validateWildcardRestHost);
        assertTrue(error.getMessage().contains(NexusRestSecurity.DOCKER_HOST_FORWARD_ADDRESS_ENVIRONMENT_VARIABLE));
    }

    @Test
    void rejectsLoopbackForwardOutsideDocker() {
        rememberAndSet(NexusRestSecurity.RUNTIME_PROPERTY, "native");
        rememberAndSet(NexusRestSecurity.EXPOSURE_MODE_PROPERTY, "loopback-forward");
        rememberAndSet(NexusRestSecurity.DOCKER_HOST_FORWARD_ADDRESS_PROPERTY, "127.0.0.1");

        IllegalStateException error = assertThrows(IllegalStateException.class, this::validateWildcardRestHost);
        assertTrue(error.getMessage().contains("NEXUS_RUNTIME=docker"));
    }

    @Test
    void acceptsDirectHttpsOnlyWithTlsMaterialAndHttpDisabled() {
        configureDirectHttpsTransport();
        assertDoesNotThrow(this::validateWildcardRestHost);
    }

    @Test
    void rejectsDirectHttpsWithoutEffectiveTlsMaterial() {
        rememberAndSet(NexusRestSecurity.RUNTIME_PROPERTY, "native");
        rememberAndSet(NexusRestSecurity.EXPOSURE_MODE_PROPERTY, "direct-https");
        rememberAndSet(NexusRestTransportPolicy.INSECURE_REQUESTS_PROPERTY, "disabled");
        rememberAndClear(NexusRestTransportPolicy.LEGACY_KEYSTORE_FILE_PROPERTY);
        rememberAndClear(NexusRestTransportPolicy.LEGACY_CERTIFICATE_FILES_PROPERTY);
        rememberAndClear(NexusRestTransportPolicy.LEGACY_KEY_FILES_PROPERTY);

        IllegalStateException error = assertThrows(IllegalStateException.class, this::validateWildcardRestHost);
        assertTrue(error.getMessage().contains("configuration TLS serveur Quarkus"));
    }

    @Test
    void rejectsDirectHttpsWhilePlainHttpListenerRemainsEnabled() {
        configureDirectHttpsTransport();
        rememberAndSet(NexusRestTransportPolicy.INSECURE_REQUESTS_PROPERTY, "enabled");

        IllegalStateException error = assertThrows(IllegalStateException.class, this::validateWildcardRestHost);
        assertTrue(error.getMessage().contains(NexusRestTransportPolicy.INSECURE_REQUESTS_PROPERTY));
    }

    @Test
    void acceptsExplicitRemoteReverseProxyHttpsWithTlsAndTrustedProxyBoundary() {
        configureReverseProxyHttpsTransport("127.0.0.1");
        assertDoesNotThrow(this::validateWildcardRestHost);
    }

    @Test
    void rejectsReverseProxyHttpsWithoutProxyAddressForwarding() {
        configureReverseProxyHttpsTransport("127.0.0.1");
        rememberAndSet(NexusRestTransportPolicy.PROXY_ADDRESS_FORWARDING_PROPERTY, "false");

        IllegalStateException error = assertThrows(IllegalStateException.class, this::validateWildcardRestHost);
        assertTrue(error.getMessage().contains(NexusRestTransportPolicy.PROXY_ADDRESS_FORWARDING_PROPERTY));
    }

    @Test
    void rejectsReverseProxyHttpsWithoutExplicitTrustedProxy() {
        configureReverseProxyHttpsTransport("127.0.0.1");
        rememberAndClear(NexusRestTransportPolicy.TRUSTED_PROXIES_PROPERTY);

        IllegalStateException error = assertThrows(IllegalStateException.class, this::validateWildcardRestHost);
        assertTrue(error.getMessage().contains(NexusRestTransportPolicy.TRUSTED_PROXIES_PROPERTY));
    }

    @Test
    void rejectsReverseProxyHttpsWithCatchAllTrustedProxy() {
        configureReverseProxyHttpsTransport("0.0.0.0/0");

        IllegalStateException error = assertThrows(IllegalStateException.class, this::validateWildcardRestHost);
        assertTrue(error.getMessage().contains("plage de confiance globale"));
    }

    @Test
    void remoteExposureStillRejectsMissingOrWeakBearerToken() {
        configureReverseProxyHttpsTransport("127.0.0.1");
        rememberAndClear(NexusRestSecurity.TOKEN_PROPERTY);
        assertThrows(IllegalStateException.class, this::validateWildcardRestHost);

        rememberAndSet(NexusRestSecurity.TOKEN_PROPERTY, "weak-token");
        IllegalStateException weak = assertThrows(IllegalStateException.class, this::validateWildcardRestHost);
        assertTrue(weak.getMessage().contains(NexusRestSecurity.TOKEN_ENVIRONMENT_VARIABLE));
    }

    private void configureDockerLoopbackForward(String declaredForward) {
        rememberAndSet(NexusRestSecurity.RUNTIME_PROPERTY, "docker");
        rememberAndSet(NexusRestSecurity.EXPOSURE_MODE_PROPERTY, "loopback-forward");
        rememberAndSet(NexusRestSecurity.DOCKER_HOST_FORWARD_ADDRESS_PROPERTY, declaredForward);
    }

    private void configureDirectHttpsTransport() {
        rememberAndSet(NexusRestSecurity.RUNTIME_PROPERTY, "native");
        rememberAndSet(NexusRestSecurity.EXPOSURE_MODE_PROPERTY, "direct-https");
        rememberAndSet(NexusRestTransportPolicy.INSECURE_REQUESTS_PROPERTY, "disabled");
        rememberAndSet(
                NexusRestTransportPolicy.LEGACY_KEYSTORE_FILE_PROPERTY,
                temporaryDirectory.resolve("server-keystore.p12").toString());
        rememberAndClear(NexusRestSecurity.DOCKER_HOST_FORWARD_ADDRESS_PROPERTY);
    }

    private void configureReverseProxyHttpsTransport(String trustedProxy) {
        configureDirectHttpsTransport();
        rememberAndSet(NexusRestSecurity.EXPOSURE_MODE_PROPERTY, "reverse-proxy-https");
        rememberAndSet(NexusRestTransportPolicy.PROXY_ADDRESS_FORWARDING_PROPERTY, "true");
        rememberAndSet(NexusRestTransportPolicy.TRUSTED_PROXIES_PROPERTY, trustedProxy);
    }

    private void validateLoopbackRestHost() {
        NexusRestExposureGuard guard = new NexusRestExposureGuard();
        guard.host = "127.0.0.1";
        guard.validateExposure();
    }

    private void validateWildcardRestHost() {
        NexusRestExposureGuard guard = new NexusRestExposureGuard();
        guard.host = "0.0.0.0";
        guard.validateExposure();
    }

    private void rememberAndSet(String name, String value) {
        previousProperties.putIfAbsent(name, System.getProperty(name));
        System.setProperty(name, value);
    }

    private void rememberAndClear(String name) {
        previousProperties.putIfAbsent(name, System.getProperty(name));
        System.clearProperty(name);
    }
}
