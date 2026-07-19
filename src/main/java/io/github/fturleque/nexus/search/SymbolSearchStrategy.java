package io.github.fturleque.nexus.search;

import io.github.fturleque.nexus.index.CodeSymbol;
import io.github.fturleque.nexus.index.IndexRepository;
import io.github.fturleque.nexus.index.IndexedSymbol;
import io.github.fturleque.nexus.project.ProjectDescriptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class SymbolSearchStrategy implements SearchStrategy {

    private static final double MIN_FUZZY_SCORE = 0.62d;

    private final IndexRepository indexRepository;

    public SymbolSearchStrategy(IndexRepository indexRepository) {
        this.indexRepository = Objects.requireNonNull(indexRepository, "indexRepository");
    }

    @Override
    public List<SearchCandidate> search(ProjectDescriptor project, String query, int limit) {
        List<String> terms = SearchText.terms(query);
        String normalizedQuery = query.toLowerCase(Locale.ROOT).trim();
        List<SearchCandidate> candidates = new ArrayList<>();

        for (IndexedSymbol indexedSymbol : indexRepository.findSymbols(project.id())) {
            CodeSymbol symbol = indexedSymbol.symbol();
            String name = symbol.name().toLowerCase(Locale.ROOT);
            String qualifiedName = symbol.qualifiedName().toLowerCase(Locale.ROOT);

            double exactScore = exactScore(normalizedQuery, terms, name, qualifiedName);
            double fuzzyScore = fuzzyScore(terms, name, qualifiedName);
            if (exactScore == 0.0d && fuzzyScore < MIN_FUZZY_SCORE) {
                continue;
            }

            Map<String, Double> signals = new LinkedHashMap<>();
            signals.put(SearchSignals.SYMBOL_EXACT, exactScore);
            signals.put(SearchSignals.SYMBOL_FUZZY, fuzzyScore);
            signals.put(SearchSignals.PATH, SearchText.pathScore(indexedSymbol.relativePath(), query));

            candidates.add(new SearchCandidate(
                    "symbol:" + indexedSymbol.relativePath() + ":" + symbol.qualifiedName(),
                    CandidateType.SYMBOL,
                    project.rootPath().resolve(indexedSymbol.relativePath()),
                    symbol,
                    symbol.signature(),
                    signals));
        }

        return candidates.stream()
                .sorted(Comparator
                        .comparingDouble(SymbolSearchStrategy::directScore).reversed()
                        .thenComparing(candidate -> candidate.path().toString())
                        .thenComparing(SearchCandidate::id))
                .limit(limit)
                .toList();
    }

    private static double exactScore(
            String normalizedQuery,
            List<String> terms,
            String name,
            String qualifiedName) {
        if (name.equals(normalizedQuery) || qualifiedName.equals(normalizedQuery)) {
            return 1.0d;
        }
        for (String term : terms) {
            if (name.equals(term) || qualifiedName.equals(term) || qualifiedName.endsWith("." + term)) {
                return 1.0d;
            }
        }
        return 0.0d;
    }

    private static double fuzzyScore(List<String> terms, String name, String qualifiedName) {
        double best = 0.0d;
        for (String term : terms) {
            if (name.contains(term) || qualifiedName.contains(term)) {
                best = Math.max(best, 0.9d);
            }
            best = Math.max(best, SearchText.similarity(name, term));
        }
        return SearchText.clamp(best);
    }

    private static double directScore(SearchCandidate candidate) {
        return Math.max(
                candidate.signals().getOrDefault(SearchSignals.SYMBOL_EXACT, 0.0d),
                candidate.signals().getOrDefault(SearchSignals.SYMBOL_FUZZY, 0.0d));
    }
}
