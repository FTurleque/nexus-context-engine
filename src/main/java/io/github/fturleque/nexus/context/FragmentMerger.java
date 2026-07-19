package io.github.fturleque.nexus.context;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Fusionne les fragments qui se chevauchent ou sont directement adjacents dans
 * un même fichier. Les signaux sont fusionnés par maximum et les raisons sont
 * dédupliquées en conservant leur ordre d'apparition.
 */
public final class FragmentMerger {

    public List<ContextFragment> merge(List<ContextFragment> fragments) {
        Objects.requireNonNull(fragments, "fragments");
        Map<Path, List<ContextFragment>> byPath = new LinkedHashMap<>();
        for (ContextFragment fragment : fragments) {
            byPath.computeIfAbsent(fragment.path(), ignored -> new ArrayList<>())
                    .add(fragment);
        }

        List<ContextFragment> result = new ArrayList<>();
        for (List<ContextFragment> pathFragments : byPath.values()) {
            List<ContextFragment> sorted = pathFragments.stream()
                    .sorted((left, right) -> {
                        int byStart = Integer.compare(left.startLine(), right.startLine());
                        if (byStart != 0) {
                            return byStart;
                        }
                        return Integer.compare(left.endLine(), right.endLine());
                    })
                    .toList();

            ContextFragment current = null;
            for (ContextFragment fragment : sorted) {
                if (current == null) {
                    current = fragment;
                    continue;
                }
                if (fragment.startLine() <= current.endLine() + 1) {
                    current = mergePair(current, fragment);
                } else {
                    result.add(current);
                    current = fragment;
                }
            }
            if (current != null) {
                result.add(current);
            }
        }
        return List.copyOf(result);
    }

    private static ContextFragment mergePair(ContextFragment left, ContextFragment right) {
        ContextFragment strongest = left.score() >= right.score() ? left : right;
        return new ContextFragment(
                strongest.type(),
                left.path(),
                mergeSymbols(left.symbol(), right.symbol()),
                Math.min(left.startLine(), right.startLine()),
                Math.max(left.endLine(), right.endLine()),
                mergeContent(left, right),
                Math.max(left.score(), right.score()),
                mergeComponents(left.scoreComponents(), right.scoreComponents()),
                mergeReasons(left.reasons(), right.reasons()));
    }

    private static String mergeContent(ContextFragment left, ContextFragment right) {
        if (right.endLine() <= left.endLine()) {
            return left.content();
        }
        String[] rightLines = right.content().split("\\R", -1);
        int overlappingLines = Math.max(0, left.endLine() - right.startLine() + 1);
        int start = Math.min(overlappingLines, rightLines.length);
        if (start >= rightLines.length) {
            return left.content();
        }
        String suffix = String.join(System.lineSeparator(),
                List.of(rightLines).subList(start, rightLines.length));
        if (suffix.isEmpty()) {
            return left.content();
        }
        return left.content() + System.lineSeparator() + suffix;
    }

    private static Map<String, Double> mergeComponents(
            Map<String, Double> left,
            Map<String, Double> right) {
        Map<String, Double> merged = new LinkedHashMap<>(left);
        right.forEach((key, value) -> merged.merge(key, value, Math::max));
        return Map.copyOf(merged);
    }

    private static List<String> mergeReasons(List<String> left, List<String> right) {
        Set<String> merged = new LinkedHashSet<>(left);
        merged.addAll(right);
        return List.copyOf(merged);
    }

    private static String mergeSymbols(String left, String right) {
        if (left == null || left.isBlank()) {
            return right;
        }
        if (right == null || right.isBlank() || left.equals(right)) {
            return left;
        }
        return left + ", " + right;
    }
}
