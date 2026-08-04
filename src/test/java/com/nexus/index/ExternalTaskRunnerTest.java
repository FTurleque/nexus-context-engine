package com.nexus.index;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalTaskRunnerTest {

    @Test
    void returnsAtTimeoutEvenWhenTheTaskIgnoresInterruption() {
        ExternalTaskRunner runner = new ExternalTaskRunner(Duration.ofMillis(50));
        long startedAt = System.nanoTime();

        IOException failure = assertThrows(IOException.class, () -> runner.run("stubborn-provider", () -> {
            long stopAt = System.nanoTime() + Duration.ofMillis(500).toNanos();
            while (System.nanoTime() < stopAt) {
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException ignored) {
                    // Simule une intégration tierce qui n'honore pas l'interruption.
                }
            }
            return "too-late";
        }));

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        assertTrue(failure.getMessage().contains("timeout global"));
        assertTrue(elapsedMillis < 400L,
                () -> "Le timeout ne doit pas attendre la fin du worker récalcitrant : " + elapsedMillis + " ms");
    }
}
