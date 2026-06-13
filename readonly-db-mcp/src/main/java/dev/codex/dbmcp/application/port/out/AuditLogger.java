package dev.codex.dbmcp.application.port.out;

public interface AuditLogger {

    void log(AuditEvent event);

    record AuditEvent(
            String toolName,
            String sql,
            Integer appliedLimit,
            boolean success,
            int rowCount,
            long elapsedMs,
            String errorMessage) {
    }
}
