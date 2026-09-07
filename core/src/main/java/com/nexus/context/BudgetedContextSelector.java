package com.nexus.context;

import com.nexus.security.SensitiveContentRedactor;
import com.nexus.token.TokenEstimator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Applique une sélection gloutonne déterministe sous budget.
 */
public final class BudgetedContextSelector {

    private static final int MIN_USEFUL_FRAGMENT_TOKENS = 24;
    private static final String TRUNCATION_MARKER = "... [fragment tronqué par NEXUS]";
    private static final Pattern LINE_SEPARATOR = Pattern.compile("\\R");

    private final TokenEstimator tokenEstimator;

    public BudgetedContextSelector(TokenEstimator tokenEstimator) {
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator");
    }

    public ContextSelectionResult select(
            List<ContextFragment> fragments,
            int tokenBudget,
            boolean explain) {
        Objects.requireNonNull(fragments, "fragments");
        if (tokenBudget <= 0) {
            throw new IllegalArgumentException("tokenBudget must be greater than zero");
        }

        // Redact before token estimation/truncation so secrets never consume artificial
        // budget and every accounting value describes the content that can actually leave
        // the selector. ContextBundle keeps a second redaction barrier at the API boundary.
        List<PreparedFragment> sorted = fragments.stream()
                .map(fragment -> new PreparedFragment(
                        fragment,
                        SensitiveContentRedactor.redact(fragment.content())))
                .sorted(Comparator
                        .comparingDouble((PreparedFragment prepared) -> prepared.fragment().score()).reversed()
                        .thenComparing(prepared -> prepared.fragment().path().toString())
                        .thenComparingInt(prepared -> prepared.fragment().startLine())
                        .thenComparingInt(prepared -> prepared.fragment().endLine()))
                .toList();

        int availableTokens = 0;
        for (PreparedFragment prepared : sorted) {
            availableTokens = saturatedAdd(
                    availableTokens,
                    tokenEstimator.estimate(prepared.content()));
        }
        int maxPerItemTokens = Math.max(MIN_USEFUL_FRAGMENT_TOKENS, tokenBudget / 2);
        int remaining = tokenBudget;
        int truncatedItems = 0;
        List<ContextItem> selected = new ArrayList<>();
        List<String> excluded = new ArrayList<>();

        for (PreparedFragment prepared : sorted) {
            ContextFragment fragment = prepared.fragment();
            String safeContent = prepared.content();
            int fullTokens = tokenEstimator.estimate(safeContent);
            int allowed = Math.min(remaining, maxPerItemTokens);

            if (fullTokens <= allowed) {
                selected.add(toItem(fragment, safeContent, fragment.endLine(), fullTokens, false, explain));
                remaining -= fullTokens;
                continue;
            }

            if (allowed >= MIN_USEFUL_FRAGMENT_TOKENS) {
                TruncatedContent truncated = truncate(safeContent, allowed);
                if (!truncated.content().isBlank() && truncated.estimatedTokens() <= remaining) {
                    int endLine = Math.min(
                            fragment.endLine(),
                            fragment.startLine() + Math.max(0, truncated.sourceLines() - 1));
                    selected.add(toItem(
                            fragment,
                            truncated.content(),
                            endLine,
                            truncated.estimatedTokens(),
                            true,
                            explain));
                    remaining -= truncated.estimatedTokens();
                    truncatedItems++;
                    continue;
                }
            }

            excluded.add(exclusion(fragment, fullTokens, remaining));
        }

        int selectedTokens = selected.stream().mapToInt(ContextItem::estimatedTokens).sum();
        return new ContextSelectionResult(
                selected,
                excluded,
                availableTokens,
                selectedTokens,
                truncatedItems);
    }

    private ContextItem toItem(
            ContextFragment fragment,
            String content,
            int endLine,
            int estimatedTokens,
            boolean truncated,
            boolean explain) {
        List<String> reasons = fragment.reasons();
        if (truncated && explain) {
            Set<String> augmented = new LinkedHashSet<>(reasons);
            augmented.add("fragment tronqué pour respecter le budget et préserver la diversité du contexte");
            reasons = List.copyOf(augmented);
        }
        return new ContextItem(
                fragment.type(),
                fragment.path(),
                fragment.symbol(),
                fragment.startLine(),
                endLine,
                content,
                fragment.score(),
                fragment.scoreComponents(),
                explain ? reasons : List.of(),
                estimatedTokens,
                truncated);
    }

    private TruncatedContent truncate(String content, int budget) {
        String[] lines = LINE_SEPARATOR.split(content, -1);
        StringBuilder builder = new StringBuilder();
        int includedLines = 0;

        for (String line : lines) {
            String candidate = builder.isEmpty()
                    ? line
                    : builder + System.lineSeparator() + line;
            String withMarker = candidate + System.lineSeparator() + TRUNCATION_MARKER;
            if (tokenEstimator.estimate(withMarker) > budget) {
                break;
            }
            builder.setLength(0);
            builder.append(candidate);
            includedLines++;
        }

        if (includedLines == lines.length) {
            String value = builder.toString();
            return new TruncatedContent(value, tokenEstimator.estimate(value), includedLines);
        }

        if (includedLines > 0) {
            String value = builder + System.lineSeparator() + TRUNCATION_MARKER;
            return new TruncatedContent(value, tokenEstimator.estimate(value), includedLines);
        }

        return truncateByCharacters(content, budget);
    }

    private TruncatedContent truncateByCharacters(String content, int budget) {
        int low = 0;
        int high = content.codePointCount(0, content.length());
        String best = "";
        while (low <= high) {
            int middleCodePoints = (low + high) >>> 1;
            int prefixEnd = content.offsetByCodePoints(0, middleCodePoints);
            String prefix = content.substring(0, prefixEnd);
            String candidate = prefix + System.lineSeparator() + TRUNCATION_MARKER;
            if (tokenEstimator.estimate(candidate) <= budget) {
                best = candidate;
                low = middleCodePoints + 1;
            } else {
                high = middleCodePoints - 1;
            }
        }
        if (best.isBlank()) {
            return new TruncatedContent("", 0, 0);
        }
        int sourceLines = Math.max(1, LINE_SEPARATOR.split(best, -1).length - 1);
        return new TruncatedContent(best, tokenEstimator.estimate(best), sourceLines);
    }

    private static int saturatedAdd(int left, int right) {
        long total = (long) left + right;
        return total >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    private static String exclusion(ContextFragment fragment, int requiredTokens, int remainingTokens) {
        return "%s:%d-%d exclu : %d tokens estimés requis, %d disponibles"
                .formatted(
                        fragment.path(),
                        fragment.startLine(),
                        fragment.endLine(),
                        requiredTokens,
                        remainingTokens);
    }

    private record PreparedFragment(ContextFragment fragment, String content) {
    }

    private record TruncatedContent(String content, int estimatedTokens, int sourceLines) {
    }
}
