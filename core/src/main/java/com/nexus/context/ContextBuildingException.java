package com.nexus.context;

public final class ContextBuildingException extends RuntimeException {

    public ContextBuildingException(String message) {
        super(message);
    }

    public ContextBuildingException(String message, Throwable cause) {
        super(message, cause);
    }
}
