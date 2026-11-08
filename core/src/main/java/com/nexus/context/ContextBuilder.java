package com.nexus.context;

import com.nexus.context.source.ContextDiscoveryBudget;
import com.nexus.search.SearchQueryContext;

import java.util.Objects;

public interface ContextBuilder {

    ContextBundle build(ContextRequest request);

    /**
     * Builds with a caller-owned physical materialization budget. Implementations
     * that do not read repository files may rely on this compatibility default.
     */
    default ContextBundle build(
            ContextRequest request,
            ContextMaterializationBudget materializationBudget) {
        Objects.requireNonNull(materializationBudget, "materializationBudget");
        return build(request);
    }

    /**
     * Builds with caller-owned materialization and native-discovery budgets.
     *
     * <p>The compatibility default preserves alternate implementations that do not
     * perform native discovery. The built-in builder overrides this method so a
     * federated caller can enforce one cumulative discovery budget and deadline
     * across the complete multi-project operation.</p>
     */
    default ContextBundle build(
            ContextRequest request,
            ContextMaterializationBudget materializationBudget,
            ContextDiscoveryBudget discoveryBudget) {
        Objects.requireNonNull(materializationBudget, "materializationBudget");
        Objects.requireNonNull(discoveryBudget, "discoveryBudget");
        return build(request, materializationBudget);
    }

    /**
     * Builds with the query context shared by every project of a federated
     * request, so costs that depend only on the query (the query embedding) are
     * paid once. The compatibility default ignores the context.
     */
    default ContextBundle build(
            ContextRequest request,
            ContextMaterializationBudget materializationBudget,
            ContextDiscoveryBudget discoveryBudget,
            SearchQueryContext queryContext) {
        Objects.requireNonNull(queryContext, "queryContext");
        return build(request, materializationBudget, discoveryBudget);
    }
}
