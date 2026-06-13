package dev.codex.dbmcp.domain.model;

public record TableSummary(
        String owner,
        String tableName,
        String comments) {
}
