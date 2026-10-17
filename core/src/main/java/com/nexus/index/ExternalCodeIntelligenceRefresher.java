package com.nexus.index;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Étape interne du pipeline d’indexation. */
final class ExternalCodeIntelligenceRefresher {
    private final IndexRepository indexRepository;
    private final List<CodeIndexImporter> codeIndexImporters;
    private final List<CodeIntelligenceProvider> codeIntelligenceProviders;
    private final ExternalTaskRunner externalTaskRunner;
    ExternalCodeIntelligenceRefresher(IndexRepository repository, List<CodeIndexImporter> importers,
            List<CodeIntelligenceProvider> providers, Duration timeout) {
        indexRepository = repository;
        codeIndexImporters = List.copyOf(importers);
        codeIntelligenceProviders = List.copyOf(providers);
        externalTaskRunner = new ExternalTaskRunner(Objects.requireNonNull(timeout, "providerTimeout"));
    }
    void refreshImportedCodeIntelligence(
            UUID projectId,
            java.nio.file.Path projectRoot,
            Set<String> scannedPaths,
            List<String> diagnostics,
            Map<String, Long> providerDurationsMs) throws IOException {
        for (CodeIndexImporter importer : codeIndexImporters) {
            long startedAt = System.nanoTime();
            CodeIntelligenceSnapshot snapshot = externalTaskRunner.run(
                    "importer " + importer.sourceProvider(),
                    () -> importer.importIndex(projectRoot, scannedPaths)
                            .orElseGet(() -> CodeIntelligenceSnapshot.empty(importer.sourceProvider())));
            validateSnapshotProvider(importer.sourceProvider(), snapshot);
            indexRepository.replaceExternalCodeIntelligence(projectId, snapshot);
            long durationMs = elapsedMillis(startedAt);
            providerDurationsMs.put("importer:" + importer.sourceProvider(), durationMs);
            diagnostics.add("importer " + importer.sourceProvider() + " : "
                    + durationMs + " ms, " + snapshot.symbols().size()
                    + " symbole(s), " + snapshot.relations().size() + " relation(s)");
        }
    }

    void refreshActiveCodeIntelligence(
            UUID projectId,
            java.nio.file.Path projectRoot,
            List<String> diagnostics,
            Map<String, Long> providerDurationsMs) throws IOException {
        for (CodeIntelligenceProvider provider : codeIntelligenceProviders) {
            long startedAt = System.nanoTime();
            CodeIntelligenceSnapshot snapshot = externalTaskRunner.run(
                    "provider " + provider.sourceProvider(),
                    () -> provider.analyze(projectRoot));
            validateSnapshotProvider(provider.sourceProvider(), snapshot);
            indexRepository.replaceExternalCodeIntelligence(projectId, snapshot);
            long durationMs = elapsedMillis(startedAt);
            providerDurationsMs.put("provider:" + provider.sourceProvider(), durationMs);
            diagnostics.add("provider " + provider.sourceProvider() + " : "
                    + durationMs + " ms, " + snapshot.symbols().size()
                    + " symbole(s), " + snapshot.relations().size() + " relation(s)");
        }
    }

    void purgeExternalCodeIntelligence(UUID projectId, Set<String> providers) {
        for (String provider : providers.stream().sorted().toList()) {
            indexRepository.replaceExternalCodeIntelligence(
                    projectId,
                    CodeIntelligenceSnapshot.empty(provider));
        }
    }

    private static void validateSnapshotProvider(String expectedProvider, CodeIntelligenceSnapshot snapshot)
            throws IOException {
        if (!expectedProvider.equals(snapshot.sourceProvider())) {
            throw new IOException("Le snapshot ne correspond pas au provider " + expectedProvider);
        }
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

}
