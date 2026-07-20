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
        ProjectGraph graph = graphBuilder.build(project.id());
        Map<String, IndexedFile> indexedFiles = indexRepository.findFiles(project.id());
        Map<String, SearchCandidate> candidates = new LinkedHashMap<>();
        for (SearchCandidate candidate : directCandidates) {
            candidates.put(candidate.id(), candidate);
        }

        for (SearchCandidate seed : directCandidates) {
            double seedScore = directScore(seed);
            if (seedScore <= 0.0d) {
                continue;
            }
            String seedPath = relativePath(project, seed);
            for (String firstHop : graph.neighbors(seedPath)) {
                addGraphSignal(project, indexedFiles, candidates, firstHop, seedScore * FIRST_HOP_FACTOR);
                for (String secondHop : graph.neighbors(firstHop)) {
                    if (!secondHop.equals(seedPath)) {
                        addGraphSignal(project, indexedFiles, candidates, secondHop, seedScore * SECOND_HOP_FACTOR);
                    }
                }
            }
        }

        return List.copyOf(candidates.values());
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
