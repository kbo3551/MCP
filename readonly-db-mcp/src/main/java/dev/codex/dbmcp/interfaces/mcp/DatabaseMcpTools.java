package dev.codex.dbmcp.interfaces.mcp;

import dev.codex.dbmcp.application.port.in.DatabaseReadUseCase;
import dev.codex.dbmcp.application.port.out.AuditLogger;
import dev.codex.dbmcp.application.port.out.AuditLogger.AuditEvent;
import dev.codex.dbmcp.domain.model.ColumnDescription;
import dev.codex.dbmcp.domain.model.QueryResult;
import dev.codex.dbmcp.domain.model.TableSummary;
import java.util.List;
import java.util.function.Supplier;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class DatabaseMcpTools {

    private final DatabaseReadUseCase useCase;
    private final AuditLogger auditLogger;

    public DatabaseMcpTools(DatabaseReadUseCase useCase, AuditLogger auditLogger) {
        this.useCase = useCase;
        this.auditLogger = auditLogger;
    }

    @McpTool(
            name = "list_tables",
            description = "List database tables for an allowed owner; use * when all owners mode is enabled")
    public List<TableSummary> listTables(
            @McpToolParam(description = "Database schema owner", required = true)
                    String owner) {
        return audited(
                "list_tables",
                "SELECT ... FROM all_tables WHERE owner = :owner",
                null,
                () -> useCase.listTables(owner),
                List::size);
    }

    @McpTool(
            name = "describe_table",
            description = "Describe columns of a database table for an allowed owner")
    public List<ColumnDescription> describeTable(
            @McpToolParam(description = "Database schema owner", required = true)
                    String owner,
            @McpToolParam(description = "Database table name", required = true)
                    String tableName) {
        return audited(
                "describe_table",
                "SELECT ... FROM all_tab_columns WHERE owner = :owner AND table_name = :tableName",
                null,
                () -> useCase.describeTable(owner, tableName),
                List::size);
    }

    @McpTool(
            name = "search_tables",
            description = "Search table names and comments across allowed owners")
    public List<TableSummary> searchTables(
            @McpToolParam(description = "Case-insensitive table keyword", required = true)
                    String keyword) {
        return audited(
                "search_tables",
                "SELECT ... FROM all_tables WHERE owner IN (:allowedOwners) AND table LIKE :keyword",
                null,
                () -> useCase.searchTables(keyword),
                List::size);
    }

    @McpTool(
            name = "run_select_query",
            description = "Execute a guarded read-only SELECT/WITH query with a row limit")
    public QueryResult runSelectQuery(
            @McpToolParam(description = "Single SELECT or WITH query", required = true)
                    String sql,
            @McpToolParam(
                            description = "Maximum rows; defaults to configured value and caps at 500",
                            required = false)
                    Integer limit) {
        long startedAt = System.nanoTime();
        try {
            QueryResult result = useCase.runSelectQuery(sql, limit);
            auditLogger.log(new AuditEvent(
                    "run_select_query",
                    sql,
                    result.appliedLimit(),
                    true,
                    result.rowCount(),
                    elapsedMs(startedAt),
                    null));
            return result;
        } catch (RuntimeException exception) {
            auditLogger.log(new AuditEvent(
                    "run_select_query",
                    sql,
                    limit,
                    false,
                    0,
                    elapsedMs(startedAt),
                    safeMessage(exception)));
            throw exception;
        }
    }

    private <T> T audited(
            String toolName,
            String sql,
            Integer appliedLimit,
            Supplier<T> action,
            RowCounter<T> rowCounter) {
        long startedAt = System.nanoTime();
        try {
            T result = action.get();
            auditLogger.log(new AuditEvent(
                    toolName,
                    sql,
                    appliedLimit,
                    true,
                    rowCounter.count(result),
                    elapsedMs(startedAt),
                    null));
            return result;
        } catch (RuntimeException exception) {
            auditLogger.log(new AuditEvent(
                    toolName,
                    sql,
                    appliedLimit,
                    false,
                    0,
                    elapsedMs(startedAt),
                    safeMessage(exception)));
            throw exception;
        }
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    private interface RowCounter<T> {
        int count(T value);
    }
}
