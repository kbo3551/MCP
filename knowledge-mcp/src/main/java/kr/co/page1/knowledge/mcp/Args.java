package kr.co.page1.knowledge.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Argument reading for tool handlers. MCP arguments arrive as loose JSON from a
 * model, so every accessor tolerates missing keys, nulls and the wrong type rather
 * than throwing - a tool that 500s on a typo teaches the model nothing.
 */
public final class Args {

    private Args() {
    }

    public static String text(JsonNode args, String field) {
        return text(args, field, null);
    }

    public static String text(JsonNode args, String field, String fallback) {
        if (args == null) {
            return fallback;
        }
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) {
            return fallback;
        }
        String value = node.isTextual() ? node.textValue() : node.toString();
        return value.isBlank() ? fallback : value;
    }

    public static String required(JsonNode args, String field) {
        String value = text(args, field, null);
        if (value == null) {
            throw new IllegalArgumentException("missing required argument: " + field);
        }
        return value;
    }

    public static boolean bool(JsonNode args, String field, boolean fallback) {
        if (args == null) {
            return fallback;
        }
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return Boolean.parseBoolean(node.asText(String.valueOf(fallback)));
    }

    public static int integer(JsonNode args, String field, int fallback) {
        if (args == null) {
            return fallback;
        }
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (node.isNumber()) {
            return node.intValue();
        }
        try {
            return Integer.parseInt(node.asText().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static Long longOrNull(JsonNode args, String field) {
        if (args == null) {
            return null;
        }
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.longValue();
        }
        try {
            return Long.parseLong(node.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Accepts a JSON array or a comma-separated string - models produce both. */
    public static List<String> stringList(JsonNode args, String field) {
        List<String> out = new ArrayList<>();
        if (args == null) {
            return out;
        }
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) {
            return out;
        }
        if (node.isArray()) {
            node.forEach(item -> {
                String value = item.isTextual() ? item.textValue() : item.toString();
                if (!value.isBlank()) {
                    out.add(value.trim());
                }
            });
            return out;
        }
        for (String part : node.asText().split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    /** Comma-joined tags, or null when absent. Stored as one column by design. */
    public static String tags(JsonNode args, String field) {
        List<String> values = stringList(args, field);
        return values.isEmpty() ? null : String.join(",", values);
    }

    public static JsonNode array(JsonNode args, String field) {
        if (args == null) {
            return null;
        }
        JsonNode node = args.get(field);
        return node != null && node.isArray() ? node : null;
    }
}
