package com.nexus.index;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.LockSupport;

/** Test support for providers that deliberately ignore interruption. */
final class NonCooperativeTaskSupport {

    private static final long PARK_NANOS = Duration.ofMillis(10).toNanos();

    private NonCooperativeTaskSupport() {
    }

    /**
     * Ignores interruption until the test opens {@code release}. The worker cannot finish before
     * the test decides it, so "the caller did not wait for the worker" is observable as state
     * rather than as a wall-clock duration. {@code safetyCap} only bounds a leaked daemon worker
     * when the test fails before releasing it.
     */
    static void ignoreInterruptsUntil(CountDownLatch release, Duration safetyCap) {
        long stopAt = System.nanoTime() + safetyCap.toNanos();
        while (release.getCount() > 0L && System.nanoTime() < stopAt) {
            LockSupport.parkNanos(PARK_NANOS);
            Thread.interrupted();
        }
    }
}
