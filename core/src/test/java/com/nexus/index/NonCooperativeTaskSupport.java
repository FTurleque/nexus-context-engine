package com.nexus.index;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

/** Test support for providers that deliberately ignore interruption. */
final class NonCooperativeTaskSupport {

    private static final long PARK_NANOS = Duration.ofMillis(10).toNanos();

    private NonCooperativeTaskSupport() {
    }

    static void ignoreInterruptsFor(Duration duration) {
        long stopAt = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < stopAt) {
            long remaining = stopAt - System.nanoTime();
            LockSupport.parkNanos(Math.min(PARK_NANOS, Math.max(1L, remaining)));
            Thread.interrupted();
        }
    }
}
