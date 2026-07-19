package com.nexus.context.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agrège les providers natifs puis déduplique les contenus équivalents.
 */
public final class ContextSourceDiscoveryService {

    public ContextSourceDiscoveryResult discover(
            List<ContextSourceProvider> providers,
            ContextSourceQuery query) throws IOException {
        List<ContextSourceDescriptor> discovered = new ArrayList<>();
        for (ContextSourceProvider provider : providers) {
            discovered.addAll(provider.discover(query));
        }

        List<ContextSourceDescriptor> sorted = discovered.stream()
                .sorted(Comparator
                        .comparingInt(ContextSourceDescriptor::priority).reversed()
                        .thenComparing(source -> source.path().toString())
                        .thenComparing(ContextSourceDescriptor::provider))
                .toList();

        Map<String, ContextSourceDescriptor> byContent = new LinkedHashMap<>();
        List<String> duplicates = new ArrayList<>();
        for (ContextSourceDescriptor source : sorted) {
            String fingerprint = fingerprint(source.content());
            ContextSourceDescriptor existing = byContent.putIfAbsent(fingerprint, source);
            if (existing != null) {
                duplicates.add(source.path() + " dédupliqué avec " + existing.path());
            }
        }
        return new ContextSourceDiscoveryResult(List.copyOf(byContent.values()), duplicates);
    }

    private static String fingerprint(String content) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n').trim();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 indisponible", impossible);
        }
    }
}
