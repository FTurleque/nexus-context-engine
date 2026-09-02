package com.nexus.context.source;

import com.nexus.search.CandidateType;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Source de contexte normalisée découverte par un provider natif.
 */
public record ContextSourceDescriptor(
        String id,
        CandidateType type,
        String provider,
        String origin,
        Path path,
        ContextSourceScope scope,
        List<String> applyTo,
        int priority,
        String content,
        Map<String, Object> metadata,
        List<String> reasons) {

    public ContextSourceDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(applyTo, "applyTo");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(reasons, "reasons");
        applyTo = List.copyOf(applyTo);
        metadata = Map.copyOf(metadata);
        reasons = List.copyOf(reasons);
        if (priority < 0 || priority > 100) {
            throw new IllegalArgumentException("priority must be between 0 and 100");
        }
    }
}
