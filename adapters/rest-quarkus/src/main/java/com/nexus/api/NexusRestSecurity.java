package com.nexus.api;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

final class NexusRestSecurity {

    static final String TOKEN_ENVIRONMENT_VARIABLE = "NEXUS_REST_API_TOKEN";
    static final String TOKEN_PROPERTY = "nexus.rest.api-token";
    static final String EXPOSURE_MODE_ENVIRONMENT_VARIABLE = "NEXUS_REST_EXPOSURE_MODE";
    static final String EXPOSURE_MODE_PROPERTY = "nexus.rest.exposure-mode";
    static final int MIN_REMOTE_TOKEN_BYTES = 32;

    private static final Set<String> REMOTE_EXPOSURE_MODES = Set.of(
            "loopback-forward",
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
        String mode = System.getProperty(EXPOSURE_MODE_PROPERTY);
        if (mode == null || mode.isBlank()) {
            mode = System.getenv(EXPOSURE_MODE_ENVIRONMENT_VARIABLE);
        }
        if (mode == null || mode.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(mode.trim().toLowerCase(Locale.ROOT));
    }

    static boolean isSupportedRemoteExposureMode(String mode) {
        return mode != null && REMOTE_EXPOSURE_MODES.contains(mode.trim().toLowerCase(Locale.ROOT));
    }

    static boolean isStrongRemoteToken(String token) {
        return token != null && token.getBytes(StandardCharsets.UTF_8).length >= MIN_REMOTE_TOKEN_BYTES;
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
