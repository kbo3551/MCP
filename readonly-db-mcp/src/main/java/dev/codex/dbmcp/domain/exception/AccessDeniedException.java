package dev.codex.dbmcp.domain.exception;

public class AccessDeniedException extends IllegalArgumentException {

    public AccessDeniedException(String message) {
        super(message);
    }
}
