package io.github.fturleque.nexus.context;

import io.github.fturleque.nexus.index.CodeSymbol;
import io.github.fturleque.nexus.ranking.RankedCandidate;
import io.github.fturleque.nexus.search.CandidateType;
import io.github.fturleque.nexus.token.TokenEstimator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Transforme les candidats classés en fragments de source concrets.
 *
 * <p>Les symboles utilisent leurs bornes AST. Les candidats fichier servent de
 * repli : le fichier complet est conservé lorsqu'il est court, sinon des
 * fenêtres lexicales sont extraites autour des termes de la requête.</p>
 */
public final class ContextFragmentFactory {

    private static final int SYMBOL_CONTEXT_LINES = 2;
    private static final int QUERY_WINDOW_LINES = 5;
    private static final int MAX_QUERY_WINDOWS = 4;
    private static final int FALLBACK_HEAD_LINES = 40;

    private final TokenEstimator tokenEstimator;

    public ContextFragmentFactory(TokenEstimator tokenEstimator) {
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator");
    }

    public List<ContextFragment> create(
            String query,
            List<RankedCandidate> rankedCandidates,
            int tokenBudget) throws IOException {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(rankedCandidates, "rankedCandidates");
        if (tokenBudget <= 0) {
            throw new IllegalArgumentException("tokenBudget must be greater than zero");
        }

        Map<Path, List<RankedCandidate>> byPath = new LinkedHashMap<>();
        for (RankedCandidate candidate : rankedCandidates) {
            byPath.computeIfAbsent(candidate.candidate().path(), ignored -> new ArrayList<>())
                    .add(candidate);
        }

        List<ContextFragment> fragments = new ArrayList<>();
        for (Map.Entry<Path, List<RankedCandidate>> entry : byPath.entrySet()) {
            Path path = entry.getKey();
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            List<RankedCandidate> symbolCandidates = entry.getValue().stream()
                    .filter(candidate -> candidate.candidate().symbol() != null)
                    .toList();

            if (!symbolCandidates.isEmpty()) {
                for (RankedCandidate candidate : symbolCandidates) {
                    fragments.add(symbolFragment(path, lines, candidate));
                }
                continue;
            }

            RankedCandidate fileCandidate = entry.getValue().getFirst();
            fragments.addAll(fileFragments(path, lines, query, fileCandidate, tokenBudget));
        }
        return List.copyOf(fragments);
    }

    private static ContextFragment symbolFragment(
            Path path,
            List<String> lines,
            RankedCandidate candidate) {
        CodeSymbol symbol = candidate.candidate().symbol();
        int start = Math.max(1, symbol.startLine() - SYMBOL_CONTEXT_LINES);
        int end = Math.min(lines.size(), symbol.endLine() + SYMBOL_CONTEXT_LINES);
        return new ContextFragment(
                candidate.candidate().type(),
                path,
                symbol.signature().isBlank() ? symbol.qualifiedName() : symbol.signature(),
                start,
                end,
                joinLines(lines, start, end),
                candidate.score(),
                candidate.components(),
                candidate.reasons());
    }

    private List<ContextFragment> fileFragments(
            Path path,
            List<String> lines,
            String query,
            RankedCandidate candidate,
            int tokenBudget) {
        if (lines.isEmpty()) {
            return List.of();
        }

        String fullContent = String.join(System.lineSeparator(), lines);
        int fullFileThreshold = Math.max(120, Math.min(800, tokenBudget / 4));
        if (tokenEstimator.estimate(fullContent) <= fullFileThreshold) {
            return List.of(new ContextFragment(
                    candidate.candidate().type(),
                    path,
                    null,
                    1,
                    lines.size(),
                    fullContent,
                    candidate.score(),
                    candidate.components(),
                    candidate.reasons()));
        }

        List<LineRange> ranges = queryRanges(lines, query);
        List<ContextFragment> fragments = new ArrayList<>(ranges.size());
        for (LineRange range : ranges) {
            fragments.add(new ContextFragment(
                    candidate.candidate().type(),
                    path,
                    null,
                    range.startLine(),
                    range.endLine(),
                    joinLines(lines, range.startLine(), range.endLine()),
                    candidate.score(),
                    candidate.components(),
                    candidate.reasons()));
        }
        return List.copyOf(fragments);
    }

    private static List<LineRange> queryRanges(List<String> lines, String query) {
        Set<String> terms = queryTerms(query);
        List<LineRange> ranges = new ArrayList<>();
        for (int index = 0; index < lines.size() && ranges.size() < MAX_QUERY_WINDOWS; index++) {
            String normalizedLine = lines.get(index).toLowerCase(Locale.ROOT);
            boolean matches = terms.stream().anyMatch(normalizedLine::contains);
            if (matches) {
                int line = index + 1;
                ranges.add(new LineRange(
                        Math.max(1, line - QUERY_WINDOW_LINES),
                        Math.min(lines.size(), line + QUERY_WINDOW_LINES)));
            }
        }

        if (ranges.isEmpty()) {
            return List.of(new LineRange(1, Math.min(lines.size(), FALLBACK_HEAD_LINES)));
        }
        return mergeRanges(ranges);
    }

    private static Set<String> queryTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        for (String term : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_$#.]+")) {
            if (term.length() >= 2) {
                terms.add(term);
            }
        }
        return Set.copyOf(terms);
    }

    private static List<LineRange> mergeRanges(List<LineRange> ranges) {
        List<LineRange> sorted = ranges.stream()
                .sorted((left, right) -> Integer.compare(left.startLine(), right.startLine()))
                .toList();
        List<LineRange> merged = new ArrayList<>();
        for (LineRange range : sorted) {
            if (merged.isEmpty()) {
                merged.add(range);
                continue;
            }
            LineRange last = merged.getLast();
            if (range.startLine() <= last.endLine() + 1) {
                merged.set(merged.size() - 1,
                        new LineRange(last.startLine(), Math.max(last.endLine(), range.endLine())));
            } else {
                merged.add(range);
            }
        }
        return List.copyOf(merged);
    }

    private static String joinLines(List<String> lines, int startLine, int endLine) {
        return String.join(System.lineSeparator(), lines.subList(startLine - 1, endLine));
    }

    private record LineRange(int startLine, int endLine) {
    }
}
