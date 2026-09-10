package com.nexus.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

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
    void rejectsAdditionalPhysicalFileOpensEvenWhenFilesAreEmpty() throws Exception {
        Path first = temporaryDirectory.resolve("first.txt");
        Path second = temporaryDirectory.resolve("second.txt");
        Files.writeString(first, "");
        Files.writeString(second, "");
        ContextMaterializationBudget budget = new ContextMaterializationLimits(1024L, 1, 1_000L).newBudget();

        assertEquals("", budget.readUtf8NoFollow(first));
        assertThrows(ContextMaterializationLimitExceededException.class, () -> budget.readUtf8NoFollow(second));
        assertEquals(1, budget.snapshot().openedFiles());
        assertEquals(0, budget.snapshot().remainingFiles());
        assertEquals(0L, budget.snapshot().cumulativeBytes());
    }

    @Test
    void rejectsWorkAfterTheMaterializationDeadlineBeforeOpeningTheFile() throws Exception {
        Path file = temporaryDirectory.resolve("deadline.txt");
        Files.writeString(file, "content");
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        ContextMaterializationBudget budget = new ContextMaterializationBudget(
                new ContextMaterializationLimits(1024L, 10, 5L),
                clock::get);
        clock.addAndGet(5_000_000L);

        assertThrows(ContextMaterializationLimitExceededException.class, () -> budget.readUtf8NoFollow(file));
        assertEquals(0, budget.snapshot().openedFiles());
        assertEquals(5L, budget.snapshot().elapsedMillis());
        assertEquals(0L, budget.snapshot().remainingDurationMillis());
    }

    @Test
    void rejectsConfiguredLimitsAboveTheSecurityCeiling() {
        assertEquals(64L, ContextMaterializationLimits.parseMaxCumulativeBytes("64"));
        assertEquals(64, ContextMaterializationLimits.parseMaxOpenedFiles("64"));
        assertEquals(64L, ContextMaterializationLimits.parseMaxDurationMillis("64"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ContextMaterializationLimits.parseMaxCumulativeBytes(
                        Long.toString(ContextMaterializationLimits.MAX_CONFIGURABLE_CUMULATIVE_BYTES + 1L)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ContextMaterializationLimits.parseMaxOpenedFiles(
                        Integer.toString(ContextMaterializationLimits.MAX_CONFIGURABLE_OPENED_FILES + 1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ContextMaterializationLimits.parseMaxDurationMillis(
                        Long.toString(ContextMaterializationLimits.MAX_CONFIGURABLE_DURATION_MILLIS + 1L)));
    }
}
