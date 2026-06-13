package dev.codex.dbmcp.domain.model;

import java.util.List;
import java.util.Map;

public record QueryResult(
        List<String> columns,
        List<Map<String, Object>> rows,
        int rowCount,
        int appliedLimit) {
}
