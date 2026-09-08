package com.nexus.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContextMaterializationBudgetTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsAFileThatExactlyFitsTheCumulativeBudget() throws Exception {
        Path file = temporaryDirectory.resolve("exact.txt");
        Files.writeString(file, "12345");
        ContextMaterializationBudget budget = new ContextMaterializationLimits(5L).newBudget();

        assertEquals("12345", budget.readUtf8NoFollow(file));
        assertEquals(1, budget.snapshot().openedFiles());
        assertEquals(5L, budget.snapshot().cumulativeBytes());
        assertEquals(0L, budget.snapshot().remainingBytes());
    }

    @Test
    void rejectsTheFirstByteBeyondTheSharedCumulativeBudget() throws Exception {
        Path file = temporaryDirectory.resolve("overflow.txt");
        Files.writeString(file, "123456");
        ContextMaterializationBudget budget = new ContextMaterializationLimits(5L).newBudget();

        assertThrows(ContextMaterializationLimitExceededException.class, () -> budget.readUtf8NoFollow(file));
        assertEquals(1, budget.snapshot().openedFiles());
        assertEquals(5L, budget.snapshot().cumulativeBytes());
        assertEquals(0L, budget.snapshot().remainingBytes());
    }

    @Test
    void rejectsConfiguredLimitsAboveTheSecurityCeiling() {
        assertEquals(64L, ContextMaterializationLimits.parseMaxCumulativeBytes("64"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ContextMaterializationLimits.parseMaxCumulativeBytes(
                        Long.toString(ContextMaterializationLimits.MAX_CONFIGURABLE_CUMULATIVE_BYTES + 1L)));
    }
}
