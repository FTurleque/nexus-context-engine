package com.nexus.context.source.git;

import java.io.IOException;
import java.util.Map;

/**
 * Source locale de contexte Git. Les implémentations doivent rester en lecture seule.
 */
public interface GitContextSourceProvider {

    String id();

    GitContextResult discover(GitContextQuery query) throws IOException;

    /**
     * Compteurs d'un éventuel cache du provider (entrées, hits, misses, évictions, capacité),
     * exposés par les métadonnées {@code explain}. Vide pour un provider sans cache.
     */
    default Map<String, Object> cacheStatistics() {
        return Map.of();
    }
}
