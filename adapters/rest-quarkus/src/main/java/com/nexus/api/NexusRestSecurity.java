package com.nexus.api;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

final class NexusRestSecurity {

    static final String TOKEN_ENVIRONMENT_VARIABLE = "NEXUS_REST_API_TOKEN";
    static final String TOKEN_PROPERTY = "nexus.rest.api-token";

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
