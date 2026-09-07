package com.nexus.context;

import com.nexus.search.CandidateType;
import com.nexus.security.SensitiveContentRedactor;
import com.nexus.token.TokenEstimator;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetedContextSelectorRedactionTest {

    @Test
    void accountsAndSelectsUsingRedactedContent() {
        TokenEstimator estimator = text -> text == null ? 0 : text.length();
        BudgetedContextSelector selector = new BudgetedContextSelector(estimator);
        String raw = "password=\"this-is-a-very-long-production-secret-value-2026\"; visibleSetting=true";
        String redacted = SensitiveContentRedactor.redact(raw);
        ContextFragment fragment = new ContextFragment(
                CandidateType.FILE,
                Path.of("config.txt"),
                null,
                1,
                1,
                raw,
                1.0d,
                Map.of(),
                List.of());

        ContextSelectionResult result = selector.select(List.of(fragment), 200, false);

        assertEquals(redacted.length(), result.availableEstimatedTokens());
        assertEquals(redacted.length(), result.selectedEstimatedTokens());
        assertEquals(1, result.items().size());
        assertEquals(redacted, result.items().getFirst().content());
        assertFalse(result.items().getFirst().content().contains("production-secret"));
        assertTrue(result.items().getFirst().content().contains("[REDACTED]"));
    }
}
