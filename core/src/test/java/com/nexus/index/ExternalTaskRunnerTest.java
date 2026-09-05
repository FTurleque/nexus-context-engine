package com.nexus.index;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

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
    void returnsAtTimeoutEvenWhenTheTaskIgnoresInterruption() {
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
    }
}
