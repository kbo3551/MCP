package dev.codex.dbmcp.domain.model;

public record ColumnDescription(
        String owner,
        String tableName,
        String columnName,
        String dataType,
        Integer dataLength,
        Integer dataPrecision,
        Integer dataScale,
        boolean nullable,
        int columnId,
        String comments) {
}
