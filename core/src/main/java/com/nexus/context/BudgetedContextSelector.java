package com.nexus.context;

import com.nexus.context.model.ContextFragment;
import com.nexus.context.model.ContextItem;
import com.nexus.context.model.ContextSelectionResult;
import com.nexus.security.OversizedSecretException;
import com.nexus.security.SensitiveContentRedactor;
import com.nexus.token.TokenEstimator;

import java.util.ArrayList;
import java.util.Arrays;
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
        // Un fragment dont une valeur sensible dépasse la borne de redaction est écarté seul.
        List<String> excluded = new ArrayList<>();
        List<PreparedFragment> redactable = new ArrayList<>(fragments.size());
        for (ContextFragment fragment : fragments) {
            try {
                redactable.add(new PreparedFragment(fragment, SensitiveContentRedactor.redact(fragment.content())));
            } catch (OversizedSecretException exception) {
                excluded.add(OversizedSecretException.exclusion(fragment.path()));
            }
        }
        List<PreparedFragment> sorted = redactable.stream()
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

    /**
     * Conserve le plus grand préfixe de lignes dont l'estimation, marqueur compris, tient dans le budget.
     *
     * <p>Les lignes sont découpées une fois et le nombre de lignes conservées est cherché par
     * dichotomie : O(log n) estimations au lieu d'une par ligne. Le résultat est celui de la
     * sélection ligne à ligne tant que {@link TokenEstimator} est croissant avec le préfixe.
     * Les lignes vides en tête du fragment ne sont pas reproduites dans le préfixe ; elles restent
     * comptées dans {@code sourceLines}.</p>
     */
    TruncatedContent truncate(String content, int budget) {
        String[] lines = LINE_SEPARATOR.split(content, -1);
        int firstNonEmpty = 0;
        while (firstNonEmpty < lines.length && lines[firstNonEmpty].isEmpty()) {
            firstNonEmpty++;
        }
        int low = 1;
        int high = lines.length;
        int includedLines = 0;
        String best = "";
        int bestTokens = 0;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            String withMarker = joinLines(lines, firstNonEmpty, middle) + System.lineSeparator() + TRUNCATION_MARKER;
            int tokens = tokenEstimator.estimate(withMarker);
            if (tokens <= budget) {
                includedLines = middle;
                best = withMarker;
                bestTokens = tokens;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }

        if (includedLines == lines.length) {
            String value = joinLines(lines, firstNonEmpty, includedLines);
            return new TruncatedContent(value, tokenEstimator.estimate(value), includedLines);
        }

        if (includedLines > 0) {
            return new TruncatedContent(best, bestTokens, includedLines);
        }

        return truncateByCharacters(content, budget);
    }

    private static String joinLines(String[] lines, int firstNonEmpty, int count) {
        int from = Math.min(firstNonEmpty, count);
        return String.join(System.lineSeparator(), Arrays.asList(lines).subList(from, count));
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

    record TruncatedContent(String content, int estimatedTokens, int sourceLines) {
    }
}
