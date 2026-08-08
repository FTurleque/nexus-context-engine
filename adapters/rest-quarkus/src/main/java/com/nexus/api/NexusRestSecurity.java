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
        return mode != null && EXPOSURE_MODES.contains(mode.trim().toLowerCase(Locale.ROOT));
    }

    static boolean isSecureNonLoopbackExposureMode(String mode) {
        return mode != null && NON_LOOPBACK_EXPOSURE_MODES.contains(mode.trim().toLowerCase(Locale.ROOT));
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
