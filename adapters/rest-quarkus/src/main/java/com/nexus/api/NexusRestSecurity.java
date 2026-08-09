package com.nexus.api;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class NexusRestSecurity {

    static final String TOKEN_ENVIRONMENT_VARIABLE = "NEXUS_REST_API_TOKEN";
    static final String TOKEN_PROPERTY = "nexus.rest.api-token";
    static final String EXPOSURE_MODE_ENVIRONMENT_VARIABLE = "NEXUS_REST_EXPOSURE_MODE";
    static final String EXPOSURE_MODE_PROPERTY = "nexus.rest.exposure-mode";
    static final String RUNTIME_ENVIRONMENT_VARIABLE = "NEXUS_RUNTIME";
    static final String RUNTIME_PROPERTY = "nexus.runtime";
    static final String DOCKER_HOST_FORWARD_ADDRESS_ENVIRONMENT_VARIABLE = "NEXUS_DOCKER_HOST_FORWARD_ADDRESS";
    static final String DOCKER_HOST_FORWARD_ADDRESS_PROPERTY = "nexus.docker.host-forward-address";
    static final int MIN_REMOTE_TOKEN_BYTES = 32;
    static final double MIN_REMOTE_TOKEN_ESTIMATED_ENTROPY_BITS = 96.0d;

    private static final Set<String> EXPOSURE_MODES = Set.of(
            "loopback-forward",
            "reverse-proxy-https",
            "direct-https");
    private static final Set<String> NON_LOOPBACK_EXPOSURE_MODES = Set.of(
            "reverse-proxy-https",
            "direct-https");

    private NexusRestSecurity() {
    }

    static Optional<String> configuredToken() {
        String token = System.getProperty(TOKEN_PROPERTY);
        if (token == null || token.isBlank()) {
            token = System.getenv(TOKEN_ENVIRONMENT_VARIABLE);
        }
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(token.trim());
    }

    static Optional<String> configuredExposureMode() {
        return configuredValue(EXPOSURE_MODE_PROPERTY, EXPOSURE_MODE_ENVIRONMENT_VARIABLE)
                .map(value -> value.toLowerCase(Locale.ROOT));
    }

    static Optional<String> configuredRuntime() {
        return configuredValue(RUNTIME_PROPERTY, RUNTIME_ENVIRONMENT_VARIABLE)
                .map(value -> value.toLowerCase(Locale.ROOT));
    }

    static Optional<String> configuredDockerHostForwardAddress() {
        return configuredValue(
                DOCKER_HOST_FORWARD_ADDRESS_PROPERTY,
                DOCKER_HOST_FORWARD_ADDRESS_ENVIRONMENT_VARIABLE);
    }

    private static Optional<String> configuredValue(String property, String environmentVariable) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(environmentVariable);
        }
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.trim());
    }

    static boolean isSupportedRemoteExposureMode(String mode) {
        return mode != null && EXPOSURE_MODES.contains(mode.trim().toLowerCase(Locale.ROOT));
    }

    static boolean isLoopbackForwardMode(String mode) {
        return mode != null && "loopback-forward".equals(mode.trim().toLowerCase(Locale.ROOT));
    }

    static boolean isSecureNonLoopbackExposureMode(String mode) {
        return mode != null && NON_LOOPBACK_EXPOSURE_MODES.contains(mode.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Validates the declared Docker forwarding contract.
     *
     * <p>NEXUS cannot introspect the Docker daemon's actual published host address from inside the
     * container. Therefore loopback-forward is accepted only when the deployment explicitly
     * declares the host forward address and that declaration is loopback. Official Compose wiring
     * derives this declaration from the exact same bind-address variable used by the port mapping.</p>
     */
    static boolean isDockerLoopbackForward(String mode) {
        return isLoopbackForwardMode(mode)
                && configuredRuntime().filter("docker"::equals).isPresent()
                && configuredDockerHostForwardAddress()
                        .filter(NexusRestSecurity::isLoopbackHost)
                        .isPresent();
    }

    static boolean isStrongRemoteToken(String token) {
        if (token == null) {
            return false;
        }
        byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
        return bytes.length >= MIN_REMOTE_TOKEN_BYTES
                && estimatedShannonEntropyBits(bytes) >= MIN_REMOTE_TOKEN_ESTIMATED_ENTROPY_BITS;
    }

    private static double estimatedShannonEntropyBits(byte[] bytes) {
        if (bytes.length == 0) {
            return 0.0d;
        }
        Map<Byte, Integer> frequencies = new HashMap<>();
        for (byte value : bytes) {
            frequencies.merge(value, 1, Integer::sum);
        }
        double bitsPerByte = 0.0d;
        for (int count : frequencies.values()) {
            double probability = (double) count / bytes.length;
            bitsPerByte -= probability * (Math.log(probability) / Math.log(2.0d));
        }
        return bitsPerByte * bytes.length;
    }

    static boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(host.trim()).isLoopbackAddress();
        } catch (UnknownHostException unresolved) {
            return false;
        }
    }

    static boolean tokenMatches(String expected, String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        String supplied = authorizationHeader.substring(7).trim();
        if (supplied.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }
}
