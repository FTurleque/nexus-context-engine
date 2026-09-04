package com.nexus.index;

/** Raised when the process-wide indexing concurrency budget is exhausted. */
public final class IndexingCapacityExceededException extends IllegalStateException {

    private final int limit;

    public IndexingCapacityExceededException(int limit) {
        super("Capacité globale d'indexation saturée (maximum " + limit + " indexation(s) simultanée(s))");
        this.limit = limit;
    }

    public int limit() {
        return limit;
    }
}
