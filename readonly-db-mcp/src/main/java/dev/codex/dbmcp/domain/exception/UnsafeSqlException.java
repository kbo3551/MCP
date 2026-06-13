package dev.codex.dbmcp.domain.exception;

public class UnsafeSqlException extends IllegalArgumentException {

    public UnsafeSqlException(String message) {
        super(message);
    }
}
