package dev.codex.dbmcp.domain.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class MaskingService {

    private static final String MASKED_VALUE = "******";
    private static final Pattern SENSITIVE_COLUMN = Pattern.compile(
            "(^|_)(PASSWORD|PWD|TOKEN|SECRET|API_KEY|EMAIL|PHONE|MOBILE|TEL|SSN|REG_NO)(_|$)");

    public List<Map<String, Object>> mask(List<Map<String, Object>> rows) {
        return rows.stream().map(this::maskRow).toList();
    }

    public Map<String, Object> maskRow(Map<String, Object> row) {
        Map<String, Object> masked = new LinkedHashMap<>();
        row.forEach((column, value) ->
                masked.put(column, isSensitive(column) && value != null ? MASKED_VALUE : value));
        return masked;
    }

    public boolean isSensitive(String columnName) {
        if (columnName == null) {
            return false;
        }
        String normalized = columnName
                .replaceAll("[^A-Za-z0-9]+", "_")
                .toUpperCase(Locale.ROOT);
        return SENSITIVE_COLUMN.matcher(normalized).find();
    }
}
