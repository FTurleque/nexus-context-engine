package com.nexus.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Un budget de tokens nul ou négatif est refusé en français par le sélecteur, avec le même texte
 * que {@link ContextBudgetPolicy}.
 */
class BudgetedContextSelectorBudgetMessageTest {

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void nonPositiveBudgetIsRejectedInFrench(int tokenBudget) {
        BudgetedContextSelector selector = new BudgetedContextSelector(CharSequence::length);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> selector.select(List.of(), tokenBudget, false));

        assertEquals("tokenBudget doit être strictement positif", failure.getMessage());
    }

    @Test
    void smallestPositiveBudgetIsAccepted() {
        BudgetedContextSelector selector = new BudgetedContextSelector(CharSequence::length);

        assertTrue(selector.select(List.of(), 1, false).items().isEmpty());
    }
}
