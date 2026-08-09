package com.nexus.context;

import com.nexus.search.CandidateType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Non-regression tests for P2: the context token budget must have a documented global upper bound
 * enforced in the core layer so that CLI, REST, MCP and federated context all inherit it.
 */
class ContextBudgetPolicyTest {

    @Test
    void rejectsZeroOrNegativeBudget() {
        assertThrows(IllegalArgumentException.class, () -> ContextBudgetPolicy.validate(0));
        assertThrows(IllegalArgumentException.class, () -> ContextBudgetPolicy.validate(-1));
    }

    @Test
    void acceptsNormalBudget() {
        assertEquals(2_000, ContextBudgetPolicy.validate(2_000));
    }

    @Test
    void acceptsBudgetExactlyAtMaximum() {
        assertEquals(
                ContextBudgetPolicy.MAX_CONTEXT_TOKEN_BUDGET,
                ContextBudgetPolicy.validate(ContextBudgetPolicy.MAX_CONTEXT_TOKEN_BUDGET));
    }

    @Test
    void rejectsBudgetAboveMaximum() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ContextBudgetPolicy.validate(ContextBudgetPolicy.MAX_CONTEXT_TOKEN_BUDGET + 1));
        assertTrue(exception.getMessage().contains("must not exceed"),
                "message d'erreur explicite sur la borne");
    }

    @Test
    void contextRequestInheritsTheUpperBound() {
        // ContextRequest est le chokepoint unique traversé par CLI/REST/MCP et le contexte fédéré.
        assertThrows(
                IllegalArgumentException.class,
                () -> new ContextRequest(
                        UUID.randomUUID(),
                        "query",
                        ContextBudgetPolicy.MAX_CONTEXT_TOKEN_BUDGET + 1,
                        Set.<CandidateType>of(),
                        Map.of(),
                        false));
    }

    @Test
    void contextRequestAcceptsBudgetAtBoundary() {
        ContextRequest request = new ContextRequest(
                UUID.randomUUID(),
                "query",
                ContextBudgetPolicy.MAX_CONTEXT_TOKEN_BUDGET,
                Set.<CandidateType>of(),
                Map.of(),
                false);
        assertEquals(ContextBudgetPolicy.MAX_CONTEXT_TOKEN_BUDGET, request.tokenBudget());
    }
}
