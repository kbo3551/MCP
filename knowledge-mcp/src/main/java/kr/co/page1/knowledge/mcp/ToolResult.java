package kr.co.page1.knowledge.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a tool hands back.
 *
 * <p>{@code text} is what the model reads - written as markdown, because the model
 * is the consumer. {@code structured} is the same answer as data, returned as
 * {@code structuredContent} for callers that want to act on it programmatically.
 */
public record ToolResult(String text, Map<String, Object> structured, boolean isError) {

    public static ToolResult ok(String text) {
        return new ToolResult(text, Map.of(), false);
    }

    public static ToolResult ok(String text, Map<String, Object> structured) {
        return new ToolResult(text, structured == null ? Map.of() : structured, false);
    }

    public static ToolResult failed(String text) {
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("error", text);
        return new ToolResult(text, structured, true);
    }
}
