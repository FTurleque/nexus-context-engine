package com.nexus.index;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalTaskRunnerTest {

    /** Borne de sûreté d'un worker jamais relâché ; aucune assertion ne porte sur cette durée. */
    private static final Duration WORKER_SAFETY_CAP = Duration.ofSeconds(30);
    private static final Duration CONDITION_TIMEOUT = Duration.ofSeconds(10);

    @Test
    void rejectsTimeoutsAboveTheProcessHardLimit() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExternalTaskRunner(ExternalTaskRunner.MAX_TIMEOUT.plusSeconds(1)));
    }

    @Test
    void returnsAtTimeoutEvenWhenTheTaskIgnoresInterruption() throws Exception {
        ExternalTaskRunner runner = new ExternalTaskRunner(Duration.ofMillis(50));
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch workerFinished = new CountDownLatch(1);

        try {
            IOException failure = assertThrows(IOException.class, () -> runner.run("stubborn-provider", () -> {
                NonCooperativeTaskSupport.ignoreInterruptsUntil(release, WORKER_SAFETY_CAP);
                workerFinished.countDown();
                return "too-late";
            }));

            assertTrue(failure.getMessage().contains("stubborn-provider"), failure.getMessage());
            assertTrue(failure.getMessage().contains("timeout global de 50 ms"), failure.getMessage());
            assertEquals(1L, workerFinished.getCount(),
                    "Le timeout ne doit pas attendre la fin du worker récalcitrant, encore bloqué");
            assertTrue(ExternalTaskRunner.status().timedOutTasks() >= 1);
            assertTrue(ExternalTaskRunner.status().activeTasks() >= 1);
        } finally {
            release.countDown();
        }

        assertTrue(workerFinished.await(CONDITION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        await(() -> ExternalTaskRunner.status().timedOutTasks() == 0, CONDITION_TIMEOUT);
    }

    @Test
    void opensCircuitBreakerUntilTimedOutWorkerActuallyStops() throws Exception {
        ExternalTaskRunner runner = new ExternalTaskRunner(Duration.ofMillis(40));
        CountDownLatch release = new CountDownLatch(1);

        try {
            assertThrows(IOException.class, () -> runner.run("circuit-provider", () -> {
                NonCooperativeTaskSupport.ignoreInterruptsUntil(release, WORKER_SAFETY_CAP);
                return "too-late";
            }));

            IOException circuitOpen = assertThrows(
                    IOException.class,
                    () -> runner.run("circuit-provider", () -> "must-not-run"));
            assertTrue(circuitOpen.getMessage().contains("Circuit-breaker ouvert"));
            assertTrue(ExternalTaskRunner.status().activeTasks() >= 1);
            assertTrue(ExternalTaskRunner.status().timedOutTasks() >= 1);
        } finally {
            release.countDown();
        }

        await(() -> ExternalTaskRunner.status().timedOutTasks() == 0, CONDITION_TIMEOUT);
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
