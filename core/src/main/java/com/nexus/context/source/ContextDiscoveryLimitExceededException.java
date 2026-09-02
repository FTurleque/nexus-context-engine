package com.nexus.context.source;

import java.io.IOException;

/** Raised when native context discovery would exceed a configured work budget. */
public final class ContextDiscoveryLimitExceededException extends IOException {

    public ContextDiscoveryLimitExceededException(String message) {
        super(message);
    }
}
