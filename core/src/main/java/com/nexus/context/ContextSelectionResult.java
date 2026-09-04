package com.nexus.context;

import java.util.List;

/**
 * Résultat interne de la politique de sélection sous budget.
 */
public record ContextSelectionResult(
        List<ContextItem> items,
        List<String> excluded,
        int availableEstimatedTokens,
        int selectedEstimatedTokens,
        int truncatedItems) {

    public ContextSelectionResult {
        items = List.copyOf(items);
        excluded = List.copyOf(excluded);
    }
}
