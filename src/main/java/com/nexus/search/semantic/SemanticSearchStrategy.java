package com.nexus.search.semantic;

import com.nexus.index.CanonicalIndexFingerprint;
import com.nexus.index.FileCategory;
import com.nexus.index.IndexRepository;
import com.nexus.project.ProjectDescriptor;
import com.nexus.search.CandidateType;
import com.nexus.search.SearchCandidate;
import com.nexus.search.SearchSignals;
import com.nexus.search.SearchStrategy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Stratégie sémantique optionnelle branchée sur les mêmes candidats que les
 * stratégies lexicales et symboliques.
 */
public final class SemanticSearchStrategy implements SearchStrategy {

    private final EmbeddingProvider embeddingProvider;
    private final SemanticSearchIndex semanticSearchIndex;
    private final IndexRepository indexRepository;
    private final String contentProfile;
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
        this.embeddingProvider = Objects.requireNonNull(embeddingProvider, "embeddingProvider");
        this.semanticSearchIndex = Objects.requireNonNull(semanticSearchIndex, "semanticSearchIndex");
        this.indexRepository = indexRepository;
        this.contentProfile = requireText(contentProfile, "contentProfile");
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
            if (!semanticSearchIndex.isCompatible(project.id(), expected)) {
                return List.of();
            }
        }

        float[] queryVector = Objects.requireNonNull(embeddingProvider.embed(query), "embedding vector");
        if (queryVector.length != embeddingProvider.dimensions()) {
            throw new IOException(
                    "Le provider d'embeddings " + embeddingProvider.modelId()
                            + " a produit un vecteur de dimension " + queryVector.length
                            + " au lieu de " + embeddingProvider.dimensions());
        }

        List<SemanticSearchHit> hits = semanticSearchIndex.search(project.id(), queryVector, limit);
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

    private String canonicalFingerprint(ProjectDescriptor project) {
        long generation = indexRepository.generation(project.id());
        CachedFingerprint cached = fingerprints.get(project.id());
        if (cached != null && cached.generation() == generation) {
            return cached.fingerprint();
        }
        String fingerprint = CanonicalIndexFingerprint.fromIndexedFiles(indexRepository.findFiles(project.id()));
        fingerprints.put(project.id(), new CachedFingerprint(generation, fingerprint));
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
