package com.nexus.search;

import com.nexus.index.CodeSymbol;
import com.nexus.index.IndexRepository;
import com.nexus.index.IndexedSymbol;
import com.nexus.project.ProjectDescriptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class SymbolSearchStrategy implements SearchStrategy {

    private static final String KEY_SEPARATOR = "\u0000";
    private static final double MIN_FUZZY_SCORE = 0.62d;
    private static final int MIN_CANDIDATE_POOL = 100;
    private static final int MAX_CANDIDATE_POOL = 2_000;
    static final int MAX_QUERY_TERMS = 8;
    static final int MAX_QUERY_TERM_CHARS = 128;

    private final IndexRepository indexRepository;

    public SymbolSearchStrategy(IndexRepository indexRepository) {
        this.indexRepository = Objects.requireNonNull(indexRepository, "indexRepository");
    }

    @Override
    public List<SearchCandidate> search(ProjectDescriptor project, String query, int limit) {
        List<String> terms = SearchText.boundedTerms(query, MAX_QUERY_TERMS, MAX_QUERY_TERM_CHARS);
        String normalizedQuery = query.toLowerCase(Locale.ROOT).trim();
        int candidatePoolLimit = Math.clamp((long) limit * 20, MIN_CANDIDATE_POOL, MAX_CANDIDATE_POOL);

        Map<String, IndexedSymbol> symbolPool = new LinkedHashMap<>();
        collect(symbolPool, indexRepository.searchSymbols(project.id(), normalizedQuery, candidatePoolLimit));
        terms.stream()
                .filter(term -> !term.equals(normalizedQuery))
                .forEach(term -> collect(
                        symbolPool,
                        indexRepository.searchSymbols(project.id(), term, candidatePoolLimit)));

        List<SearchCandidate> candidates = new ArrayList<>();
        for (IndexedSymbol indexedSymbol : symbolPool.values()) {
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
            signals.put(SearchSignals.PATH, SearchText.pathScore(indexedSymbol.relativePath(), terms));

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

    private static void collect(Map<String, IndexedSymbol> target, List<IndexedSymbol> symbols) {
        for (IndexedSymbol indexed : symbols) {
            CodeSymbol symbol = indexed.symbol();
            String key = indexed.relativePath() + KEY_SEPARATOR + symbol.kind() + KEY_SEPARATOR
                    + symbol.qualifiedName() + KEY_SEPARATOR + symbol.startLine() + KEY_SEPARATOR
                    + symbol.sourceProvider();
            target.putIfAbsent(key, indexed);
        }
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
