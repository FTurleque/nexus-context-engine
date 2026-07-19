package com.nexus.search;

import com.nexus.index.CodeSymbol;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CandidateMerger {

    public List<SearchCandidate> merge(List<SearchCandidate> candidates) {
        Map<String, SearchCandidate> merged = new LinkedHashMap<>();
        for (SearchCandidate candidate : candidates) {
            merged.merge(candidate.id(), candidate, CandidateMerger::mergeCandidate);
        }
        return List.copyOf(merged.values());
    }

    public SearchCandidate merge(SearchCandidate left, SearchCandidate right) {
        if (!left.id().equals(right.id())) {
            throw new IllegalArgumentException("Cannot merge candidates with different ids");
        }
        return mergeCandidate(left, right);
    }

    private static SearchCandidate mergeCandidate(SearchCandidate left, SearchCandidate right) {
        Map<String, Double> signals = new LinkedHashMap<>(left.signals());
        right.signals().forEach((name, value) -> signals.merge(name, value, Math::max));

        CodeSymbol symbol = left.symbol() != null ? left.symbol() : right.symbol();
        String excerpt = !left.excerpt().isBlank() ? left.excerpt() : right.excerpt();
        Path path = left.path();
        CandidateType type = preferredType(left.type(), right.type());

        return new SearchCandidate(left.id(), type, path, symbol, excerpt, signals);
    }

    private static CandidateType preferredType(CandidateType left, CandidateType right) {
        List<CandidateType> order = new ArrayList<>(List.of(
                CandidateType.SYMBOL,
                CandidateType.TEST,
                CandidateType.FILE,
                CandidateType.DOCUMENTATION,
                CandidateType.INSTRUCTION,
                CandidateType.GIT));
        return order.indexOf(left) <= order.indexOf(right) ? left : right;
    }
}
