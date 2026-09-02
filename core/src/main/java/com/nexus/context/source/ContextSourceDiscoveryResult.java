package com.nexus.context.source;

import java.util.List;

public record ContextSourceDiscoveryResult(
        List<ContextSourceDescriptor> sources,
        List<String> deduplicatedSources) {

    public ContextSourceDiscoveryResult {
        sources = List.copyOf(sources);
        deduplicatedSources = List.copyOf(deduplicatedSources);
    }
}
