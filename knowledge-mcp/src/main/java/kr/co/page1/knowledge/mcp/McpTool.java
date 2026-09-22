package kr.co.page1.knowledge.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.Function;

/**
 * One callable tool.
 *
 * @param inputSchemaJson raw JSON Schema for the arguments. Kept as a string so the
 *                        schema reads like schema at the definition site instead of
 *                        being assembled node by node.
 */
public record McpTool(
        String name,
        String title,
        String description,
        String inputSchemaJson,
        Function<JsonNode, ToolResult> handler) {
}
