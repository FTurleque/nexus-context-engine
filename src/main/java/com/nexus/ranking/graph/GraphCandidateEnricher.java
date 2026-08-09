package com.nexus.ranking.graph;

import com.nexus.index.FileCategory;
import com.nexus.index.IndexRepository;
import com.nexus.index.IndexedFile;
import com.nexus.project.ProjectDescriptor;
import com.nexus.search.CandidateEnricher;
import com.nexus.search.CandidateMerger;
import com.nexus.search.CandidateType;
import com.nexus.search.SearchCandidate;
import com.nexus.search.SearchSignals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class GraphCandidateEnricher implements CandidateEnricher {

    private static final double FIRST_HOP_FACTOR = 0.65d;
    private static final double SECOND_HOP_FACTOR = 0.35d;

    private final IndexRepository indexRepository;
    private final ProjectGraphBuilder graphBuilder;
    private final CandidateMerger candidateMerger;

    public GraphCandidateEnricher(IndexRepository indexRepository) {
        this.indexRepository = Objects.requireNonNull(indexRepository, "indexRepository");
        this.graphBuilder = new ProjectGraphBuilder(indexRepository);
        this.candidateMerger = new CandidateMerger();
    }

    @Override
    public List<SearchCandidate> enrich(ProjectDescriptor project, List<SearchCandidate> directCandidates) {
        Map<String, Double> graphScores = graphScores(project, directCandidates);
        Map<String, IndexedFile> indexedFiles = indexRepository.findFiles(project.id(), graphScores.keySet());
        Map<String, SearchCandidate> candidates = new LinkedHashMap<>();
        for (SearchCandidate candidate : directCandidates) {
            candidates.put(candidate.id(), candidate);
        }
        graphScores.forEach((relativePath, graphScore) ->
                addGraphSignal(project, indexedFiles, candidates, relativePath, graphScore));
        return List.copyOf(candidates.values());
    }

    private Map<String, Double> graphScores(
            ProjectDescriptor project,
            List<SearchCandidate> directCandidates) {
        Map<String, Double> seedScores = new LinkedHashMap<>();
        for (SearchCandidate seed : directCandidates) {
            double seedScore = directScore(seed);
            if (seedScore > 0.0d) {
                seedScores.merge(relativePath(project, seed), seedScore, Math::max);
            }
        }
        if (seedScores.isEmpty()) {
            return Map.of();
        }

        Map<String, Set<String>> firstHop = graphBuilder.neighbors(project.id(), seedScores.keySet());
        Map<String, Double> firstHopBaseScores = new LinkedHashMap<>();
        Map<String, Double> scores = new LinkedHashMap<>();
        firstHop.forEach((seedPath, neighbors) -> {
            double seedScore = seedScores.getOrDefault(seedPath, 0.0d);
            for (String neighbor : neighbors) {
                firstHopBaseScores.merge(neighbor, seedScore, Math::max);
                scores.merge(neighbor, seedScore * FIRST_HOP_FACTOR, Math::max);
            }
        });

        if (firstHopBaseScores.isEmpty()) {
            return Map.copyOf(scores);
        }

        Map<String, Set<String>> secondHop = graphBuilder.neighbors(project.id(), firstHopBaseScores.keySet());
        secondHop.forEach((firstHopPath, neighbors) -> {
            double originatingSeedScore = firstHopBaseScores.getOrDefault(firstHopPath, 0.0d);
            for (String neighbor : neighbors) {
                if (!neighbor.equals(firstHopPath)) {
                    scores.merge(neighbor, originatingSeedScore * SECOND_HOP_FACTOR, Math::max);
                }
            }
        });
        return Map.copyOf(scores);
    }

    private void addGraphSignal(
            ProjectDescriptor project,
            Map<String, IndexedFile> indexedFiles,
            Map<String, SearchCandidate> candidates,
            String relativePath,
            double graphScore) {
        IndexedFile indexedFile = indexedFiles.get(relativePath);
        if (indexedFile == null) {
            return;
        }
        String id = "file:" + relativePath;
        SearchCandidate graphCandidate = new SearchCandidate(
                id,
                indexedFile.category() == FileCategory.TEST ? CandidateType.TEST : CandidateType.FILE,
                project.rootPath().resolve(relativePath),
                null,
                relativePath,
                Map.of(SearchSignals.GRAPH, Math.min(1.0d, graphScore)));
        candidates.merge(id, graphCandidate, candidateMerger::merge);
    }

    private static String relativePath(ProjectDescriptor project, SearchCandidate candidate) {
        return project.rootPath().relativize(candidate.path()).toString().replace('\\', '/');
    }

    private static double directScore(SearchCandidate candidate) {
        List<String> directSignals = new ArrayList<>(List.of(
                SearchSignals.LEXICAL,
                SearchSignals.SYMBOL_EXACT,
                SearchSignals.SYMBOL_FUZZY,
                SearchSignals.PATH));
        return directSignals.stream()
                .mapToDouble(signal -> candidate.signals().getOrDefault(signal, 0.0d))
                .max()
                .orElse(0.0d);
    }
}
