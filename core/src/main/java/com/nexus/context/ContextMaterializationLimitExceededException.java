package com.nexus.context;

import java.io.IOException;

/** Raised when a context request exhausts its cumulative task-materialization I/O budget. */
final class ContextMaterializationLimitExceededException extends IOException {

    ContextMaterializationLimitExceededException(String message) {
        super(message);
    }
}
