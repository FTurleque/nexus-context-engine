package io.github.fturleque.nexus.token;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeuristicTokenEstimatorTest {

    private final HeuristicTokenEstimator estimator = new HeuristicTokenEstimator();

    @Test
    void estimatesDeterministicallyAndHandlesUnicodeCodePoints() {
        assertEquals(0, estimator.estimate(""));
        assertEquals(estimator.estimate("class Demo {}"), estimator.estimate("class Demo {}"));
        assertTrue(estimator.estimate("class Demo { void run() {} }") > 0);
        assertEquals(1, estimator.estimate("😀"));
    }
}
