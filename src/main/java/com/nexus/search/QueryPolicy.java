package com.nexus.search;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Politique commune de validation des requêtes publiques NEXUS.
 *
 * <p>La borne est exprimée en octets UTF-8, et non en nombre de caractères,
 * afin de refléter le coût réel lorsque la requête traverse JSON, HTTP, MCP,
 * les fournisseurs sémantiques ou d'autres frontières textuelles.</p>
 */
public final class QueryPolicy {

    public static final int MAX_QUERY_UTF8_BYTES = 16 * 1024;

    private QueryPolicy() {
    }

    public static String normalize(String value) {
        Objects.requireNonNull(value, "query");
        String normalized = value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("La requête ne peut pas être vide");
        }

        // Fast-path sans allocation d'un tableau UTF-8 lorsqu'une requête ASCII
        // ou majoritairement ASCII dépasse déjà nécessairement la borne.
        if (normalized.length() > MAX_QUERY_UTF8_BYTES) {
            throw tooLarge(normalized.length());
        }

        int utf8Bytes = normalized.getBytes(StandardCharsets.UTF_8).length;
        if (utf8Bytes > MAX_QUERY_UTF8_BYTES) {
            throw tooLarge(utf8Bytes);
        }
        return normalized;
    }

    public static int utf8Length(String value) {
        return Objects.requireNonNull(value, "query").getBytes(StandardCharsets.UTF_8).length;
    }

    private static IllegalArgumentException tooLarge(int measuredBytesOrMinimum) {
        return new IllegalArgumentException(
                "La requête dépasse la limite de " + MAX_QUERY_UTF8_BYTES
                        + " octets UTF-8 (taille mesurée ou minimale "
                        + measuredBytesOrMinimum + " octets)");
    }
}
