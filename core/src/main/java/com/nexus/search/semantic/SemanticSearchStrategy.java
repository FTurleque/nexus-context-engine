package com.nexus.search.semantic;

import com.nexus.index.CanonicalIndexFingerprint;
import com.nexus.index.FileCategory;
import com.nexus.index.IndexRepository;
import com.nexus.project.ProjectDescriptor;
import com.nexus.search.CandidateType;
import com.nexus.search.SearchCandidate;
import com.nexus.search.SearchSignals;
import com.nexus.search.SearchStrategy;
import com.nexus.security.SensitiveContentRedactor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;

/**
 * Stratégie sémantique optionnelle branchée sur les mêmes candidats que les
 * stratégies lexicales et symboliques.
 */
public final class SemanticSearchStrategy implements SearchStrategy {

    private static final System.Logger LOGGER = System.getLogger(SemanticSearchStrategy.class.getName());

    private final EmbeddingProvider embeddingProvider;
    private final SemanticSearchIndex semanticSearchIndex;
    private final IndexRepository indexRepository;
    private final String contentProfile;
    private final BiConsumer<String, Throwable> degradationReporter;
    private final ConcurrentMap<UUID, CachedFingerprint> fingerprints = new ConcurrentHashMap<>();

    public SemanticSearchStrategy(
            EmbeddingProvider embeddingProvider,
            SemanticSearchIndex semanticSearchIndex) {
        this(
                embeddingProvider,
                semanticSearchIndex,
                null,
                SemanticIndexingService.defaultProfileId());
    }

    public SemanticSearchStrategy(
            EmbeddingProvider embeddingProvider,
            SemanticSearchIndex semanticSearchIndex,
            IndexRepository indexRepository) {
        this(
                embeddingProvider,
                semanticSearchIndex,
                indexRepository,
                SemanticIndexingService.defaultProfileId());
    }

    public SemanticSearchStrategy(
            EmbeddingProvider embeddingProvider,
            SemanticSearchIndex semanticSearchIndex,
            IndexRepository indexRepository,
            String contentProfile) {
        this(
                embeddingProvider,
                semanticSearchIndex,
                indexRepository,
                contentProfile,
                (message, failure) -> LOGGER.log(System.Logger.Level.WARNING, message, failure));
    }

    SemanticSearchStrategy(
            EmbeddingProvider embeddingProvider,
            SemanticSearchIndex semanticSearchIndex,
            IndexRepository indexRepository,
            String contentProfile,
            BiConsumer<String, Throwable> degradationReporter) {
        this.embeddingProvider = Objects.requireNonNull(embeddingProvider, "embeddingProvider");
        this.semanticSearchIndex = Objects.requireNonNull(semanticSearchIndex, "semanticSearchIndex");
        this.indexRepository = indexRepository;
        this.contentProfile = requireText(contentProfile, "contentProfile");
        this.degradationReporter = Objects.requireNonNull(degradationReporter, "degradationReporter");
        if (embeddingProvider.dimensions() <= 0) {
            throw new IllegalArgumentException("embeddingProvider dimensions must be greater than zero");
        }
        if (semanticSearchIndex.dimensions() != embeddingProvider.dimensions()) {
            throw new IllegalArgumentException(
                    "semantic index dimensions must match embedding provider dimensions");
        }
    }

    @Override
    public List<SearchCandidate> search(ProjectDescriptor project, String query, int limit) throws IOException {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(query, "query");
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }

        if (indexRepository != null) {
            String canonicalFingerprint = canonicalFingerprint(project);
            SemanticIndexProvenance expected = SemanticIndexProvenance.current(
                    canonicalFingerprint,
                    embeddingProvider,
                    contentProfile);
            final boolean compatible;
            try {
                compatible = semanticSearchIndex.isCompatible(project.id(), expected);
            } catch (IOException exception) {
                return degradedIndex(project, exception);
            }
            if (!compatible) {
                return List.of();
            }
        }

        final float[] queryVector;
        try {
            String embeddingQuery = SensitiveContentRedactor.redact(query);
            queryVector = Objects.requireNonNull(embeddingProvider.embed(embeddingQuery), "embedding vector");
        } catch (EmbeddingProviderUnavailableException exception) {
            return degradedProvider(project, exception);
        }
        if (queryVector.length != embeddingProvider.dimensions()) {
            throw new IOException(
                    "Le provider d'embeddings " + embeddingProvider.modelId()
                            + " a produit un vecteur de dimension " + queryVector.length
                            + " au lieu de " + embeddingProvider.dimensions());
        }

        final List<SemanticSearchHit> hits;
        try {
            hits = semanticSearchIndex.search(project.id(), queryVector, limit);
        } catch (IOException exception) {
            return degradedIndex(project, exception);
        }
        List<SearchCandidate> candidates = new ArrayList<>(hits.size());
        for (SemanticSearchHit hit : hits) {
            if (!isGenericSearchEligible(hit.category()) || hit.score() <= 0.0d) {
                continue;
            }
            Map<String, Double> signals = new LinkedHashMap<>();
            signals.put(SearchSignals.SEMANTIC, hit.score());
            candidates.add(new SearchCandidate(
                    "file:" + hit.relativePath(),
                    candidateType(hit.category()),
                    project.rootPath().resolve(hit.relativePath()),
                    null,
                    hit.excerpt(),
                    signals));
            if (candidates.size() >= limit) {
                break;
            }
        }
        return List.copyOf(candidates);
    }

    private List<SearchCandidate> degradedProvider(
            ProjectDescriptor project,
            EmbeddingProviderUnavailableException failure) {
        degradationReporter.accept(
                "semantic-search degraded: code=embedding_provider_unavailable project=" + project.id()
                        + " provider=" + embeddingProvider.modelId()
                        + "; fallback=lexical-symbolic; retry=automatic",
                failure);
        return List.of();
    }

    private List<SearchCandidate> degradedIndex(ProjectDescriptor project, IOException failure) {
        degradationReporter.accept(
                "semantic-search degraded: code=semantic_index_unavailable project=" + project.id()
                        + "; fallback=lexical-symbolic; recovery=\"nexus index "
                        + project.id() + " --rebuild\"",
                failure);
        return List.of();
    }

    private String canonicalFingerprint(ProjectDescriptor project) {
        long generation = indexRepository.generation(project.id());
        CachedFingerprint cached = fingerprints.get(project.id());
        if (generation > 0L && cached != null && cached.generation() == generation) {
            return cached.fingerprint();
        }
        String fingerprint = CanonicalIndexFingerprint.fromIndexedFiles(indexRepository.findFiles(project.id()));
        if (generation > 0L) {
            fingerprints.put(project.id(), new CachedFingerprint(generation, fingerprint));
        }
        return fingerprint;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static boolean isGenericSearchEligible(FileCategory category) {
        return category != FileCategory.INSTRUCTION
                && category != FileCategory.AGENT_PROFILE
                && category != FileCategory.SKILL;
    }

    private static CandidateType candidateType(FileCategory category) {
        return switch (category) {
            case TEST -> CandidateType.TEST;
            case DOCUMENTATION -> CandidateType.DOCUMENTATION;
            default -> CandidateType.FILE;
        };
    }

    private record CachedFingerprint(long generation, String fingerprint) {
    }
}
