package com.nexus.index;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalTaskRunnerTest {

    @Test
    void rejectsTimeoutsAboveTheProcessHardLimit() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExternalTaskRunner(ExternalTaskRunner.MAX_TIMEOUT.plusSeconds(1)));
    }

    @Test
    void returnsAtTimeoutEvenWhenTheTaskIgnoresInterruption() throws Exception {
        ExternalTaskRunner runner = new ExternalTaskRunner(Duration.ofMillis(50));
        long startedAt = System.nanoTime();

        IOException failure = assertThrows(IOException.class, () -> runner.run("stubborn-provider", () -> {
            NonCooperativeTaskSupport.ignoreInterruptsFor(Duration.ofMillis(1_500));
            return "too-late";
        }));

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        assertTrue(failure.getMessage().contains("timeout global"));
        assertTrue(elapsedMillis < 750L,
                () -> "Le timeout ne doit pas attendre la fin du worker récalcitrant : " + elapsedMillis + " ms");
        assertTrue(ExternalTaskRunner.status().timedOutTasks() >= 1);
        await(() -> ExternalTaskRunner.status().timedOutTasks() == 0, Duration.ofSeconds(3));
    }

    @Test
    void opensCircuitBreakerUntilTimedOutWorkerActuallyStops() throws Exception {
        ExternalTaskRunner runner = new ExternalTaskRunner(Duration.ofMillis(40));

        assertThrows(IOException.class, () -> runner.run("circuit-provider", () -> {
            NonCooperativeTaskSupport.ignoreInterruptsFor(Duration.ofMillis(500));
            return "too-late";
        }));

        IOException circuitOpen = assertThrows(
                IOException.class,
                () -> runner.run("circuit-provider", () -> "must-not-run"));
        assertTrue(circuitOpen.getMessage().contains("Circuit-breaker ouvert"));
        assertTrue(ExternalTaskRunner.status().activeTasks() >= 1);
        assertTrue(ExternalTaskRunner.status().timedOutTasks() >= 1);

        await(() -> ExternalTaskRunner.status().timedOutTasks() == 0, Duration.ofSeconds(2));
        assertEquals("recovered", runner.run("circuit-provider", () -> "recovered"));
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue(condition.getAsBoolean(), "Condition non satisfaite avant le timeout de test");
    }
}
