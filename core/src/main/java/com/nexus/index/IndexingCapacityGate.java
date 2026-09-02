package com.nexus.index;

import java.util.concurrent.Semaphore;

/** Process-wide non-blocking concurrency budget for expensive project indexing runs. */
final class IndexingCapacityGate {

    static final String MAX_CONCURRENT_INDEXING_ENVIRONMENT_VARIABLE = "NEXUS_MAX_CONCURRENT_INDEXING";
    static final int DEFAULT_MAX_CONCURRENT_INDEXING = 2;
    static final int MAX_CONFIGURED_CONCURRENT_INDEXING = 16;

    private static volatile IndexingCapacityGate shared;

    private final int limit;
    private final Semaphore capacity;

    IndexingCapacityGate(int limit) {
        if (limit <= 0 || limit > MAX_CONFIGURED_CONCURRENT_INDEXING) {
            throw new IllegalArgumentException(
                    "maxConcurrentIndexing doit être compris entre 1 et " + MAX_CONFIGURED_CONCURRENT_INDEXING);
        }
        this.limit = limit;
        this.capacity = new Semaphore(limit, true);
    }

    static Permit acquireShared() {
        return shared().acquire();
    }

    Permit acquire() {
        if (!capacity.tryAcquire()) {
            throw new IndexingCapacityExceededException(limit);
        }
        return new Permit(capacity);
    }

    private static IndexingCapacityGate shared() {
        IndexingCapacityGate current = shared;
        if (current != null) {
            return current;
        }
        synchronized (IndexingCapacityGate.class) {
            if (shared == null) {
                shared = new IndexingCapacityGate(configuredLimit());
            }
            return shared;
        }
    }

    private static int configuredLimit() {
        String configured = System.getenv(MAX_CONCURRENT_INDEXING_ENVIRONMENT_VARIABLE);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_MAX_CONCURRENT_INDEXING;
        }
        try {
            int parsed = Integer.parseInt(configured.trim());
            if (parsed <= 0 || parsed > MAX_CONFIGURED_CONCURRENT_INDEXING) {
                throw new IllegalArgumentException(
                        MAX_CONCURRENT_INDEXING_ENVIRONMENT_VARIABLE + " doit être compris entre 1 et "
                                + MAX_CONFIGURED_CONCURRENT_INDEXING);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    MAX_CONCURRENT_INDEXING_ENVIRONMENT_VARIABLE + " doit être un entier", exception);
        }
    }

    static final class Permit implements AutoCloseable {
        private final Semaphore capacity;
        private boolean released;

        private Permit(Semaphore capacity) {
            this.capacity = capacity;
        }

        @Override
        public void close() {
            if (!released) {
                released = true;
                capacity.release();
            }
        }
    }
}
