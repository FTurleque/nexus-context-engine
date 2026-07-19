package io.github.fturleque.nexus.search.lucene;

import io.github.fturleque.nexus.index.FileCategory;
import io.github.fturleque.nexus.project.ProjectDescriptor;
import io.github.fturleque.nexus.search.CandidateType;
import io.github.fturleque.nexus.search.LexicalSearchHit;
import io.github.fturleque.nexus.search.SearchCandidate;
import io.github.fturleque.nexus.search.SearchIndex;
import io.github.fturleque.nexus.search.SearchSignals;
import io.github.fturleque.nexus.search.SearchStrategy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class LuceneFileSearchStrategy implements SearchStrategy {

    private final SearchIndex searchIndex;

    public LuceneFileSearchStrategy(SearchIndex searchIndex) {
        this.searchIndex = Objects.requireNonNull(searchIndex, "searchIndex");
    }

    @Override
    public List<SearchCandidate> search(ProjectDescriptor project, String query, int limit) throws IOException {
        List<LexicalSearchHit> hits = searchIndex.search(project.id(), query, limit);
        if (hits.isEmpty()) {
            return List.of();
        }

        double maxScore = hits.stream().mapToDouble(LexicalSearchHit::score).max().orElse(1.0d);
        List<SearchCandidate> candidates = new ArrayList<>(hits.size());
        for (LexicalSearchHit hit : hits) {
            Map<String, Double> signals = new LinkedHashMap<>();
            signals.put(SearchSignals.LEXICAL, maxScore == 0.0d ? 0.0d : hit.score() / maxScore);
            signals.put(SearchSignals.PATH, pathScore(hit.relativePath(), query));
            candidates.add(new SearchCandidate(
                    "file:" + hit.relativePath(),
                    hit.category() == FileCategory.TEST ? CandidateType.TEST : CandidateType.FILE,
                    project.rootPath().resolve(hit.relativePath()),
                    null,
                    hit.relativePath(),
                    signals));
        }
        return List.copyOf(candidates);
    }

    private static double pathScore(String relativePath, String query) {
        String normalizedPath = relativePath.toLowerCase();
        String[] terms = query.toLowerCase().split("[^\\p{L}\\p{N}_$#.]+");
        int total = 0;
        int matches = 0;
        for (String term : terms) {
            if (term.isBlank()) {
                continue;
            }
            total++;
            if (normalizedPath.contains(term)) {
                matches++;
            }
        }
        return total == 0 ? 0.0d : (double) matches / total;
    }
}
