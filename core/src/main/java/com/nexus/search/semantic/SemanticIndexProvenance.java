package com.nexus.search.semantic;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Provenance persistée d'un index sémantique dérivé.
 */
public record SemanticIndexProvenance(
        String canonicalFingerprint,
        String providerId,
        String modelId,
        int dimensions,
        String contentProfile,
        int schemaVersion) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    private static final String PREFIX = "nexus.semantic.";
    private static final String CANONICAL_FINGERPRINT = PREFIX + "canonicalFingerprint";
    private static final String PROVIDER_ID = PREFIX + "providerId";
    private static final String MODEL_ID = PREFIX + "modelId";
    private static final String DIMENSIONS_KEY = PREFIX + "dimensions";
    private static final String CONTENT_PROFILE = PREFIX + "contentProfile";
    private static final String SCHEMA_VERSION = PREFIX + "schemaVersion";

    public SemanticIndexProvenance {
        canonicalFingerprint = requireText(canonicalFingerprint, "canonicalFingerprint");
        providerId = requireText(providerId, "providerId");
        modelId = requireText(modelId, "modelId");
        contentProfile = requireText(contentProfile, "contentProfile");
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be greater than zero");
        }
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be greater than zero");
        }
    }

    public static SemanticIndexProvenance current(
            String canonicalFingerprint,
            EmbeddingProvider embeddingProvider) {
        return current(
                canonicalFingerprint,
                embeddingProvider,
                SemanticIndexingService.defaultProfileId());
    }

    public static SemanticIndexProvenance current(
            String canonicalFingerprint,
            EmbeddingProvider embeddingProvider,
            String contentProfile) {
        Objects.requireNonNull(embeddingProvider, "embeddingProvider");
        return new SemanticIndexProvenance(
                canonicalFingerprint,
                embeddingProvider.providerId(),
                embeddingProvider.modelId(),
                embeddingProvider.dimensions(),
                contentProfile,
                CURRENT_SCHEMA_VERSION);
    }

    public Map<String, String> asCommitData() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put(CANONICAL_FINGERPRINT, canonicalFingerprint);
        data.put(PROVIDER_ID, providerId);
        data.put(MODEL_ID, modelId);
        data.put(DIMENSIONS_KEY, Integer.toString(dimensions));
        data.put(CONTENT_PROFILE, contentProfile);
        data.put(SCHEMA_VERSION, Integer.toString(schemaVersion));
        return Map.copyOf(data);
    }

    public boolean matches(Map<String, String> commitData) {
        Objects.requireNonNull(commitData, "commitData");
        return canonicalFingerprint.equals(commitData.get(CANONICAL_FINGERPRINT))
                && providerId.equals(commitData.get(PROVIDER_ID))
                && modelId.equals(commitData.get(MODEL_ID))
                && Integer.toString(dimensions).equals(commitData.get(DIMENSIONS_KEY))
                && contentProfile.equals(commitData.get(CONTENT_PROFILE))
                && Integer.toString(schemaVersion).equals(commitData.get(SCHEMA_VERSION));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
