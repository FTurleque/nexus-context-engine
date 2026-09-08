package com.nexus.context;

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
}
