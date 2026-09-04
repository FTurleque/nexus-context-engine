package com.nexus.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResultLimitPolicyTest {

    @Test
    void publicLimitRemainsStrictlyBounded() {
        assertEquals(ResultLimitPolicy.MAX_RESULT_LIMIT,
                ResultLimitPolicy.validate(ResultLimitPolicy.MAX_RESULT_LIMIT));
        assertThrows(IllegalArgumentException.class,
                () -> ResultLimitPolicy.validate(ResultLimitPolicy.MAX_RESULT_LIMIT + 1));
    }

    @Test
    void internalRetrievalAllowsOnlyBoundedOverfetch() {
        assertEquals(ResultLimitPolicy.MAX_INTERNAL_RETRIEVAL_LIMIT,
                ResultLimitPolicy.MAX_RESULT_LIMIT * 4);
        assertEquals(ResultLimitPolicy.MAX_INTERNAL_RETRIEVAL_LIMIT,
                ResultLimitPolicy.validateInternalRetrieval(ResultLimitPolicy.MAX_INTERNAL_RETRIEVAL_LIMIT));
        assertThrows(IllegalArgumentException.class,
                () -> ResultLimitPolicy.validateInternalRetrieval(
                        ResultLimitPolicy.MAX_INTERNAL_RETRIEVAL_LIMIT + 1));
    }

    @Test
    void bothPoliciesRejectNonPositiveLimits() {
        assertThrows(IllegalArgumentException.class, () -> ResultLimitPolicy.validate(0));
        assertThrows(IllegalArgumentException.class, () -> ResultLimitPolicy.validateInternalRetrieval(0));
    }
}
