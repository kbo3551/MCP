package kr.co.page1.knowledge.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import kr.co.page1.knowledge.config.KnowledgeProperties;
import kr.co.page1.knowledge.domain.PatternCategory;
import kr.co.page1.knowledge.service.ContextBundleService;
import kr.co.page1.knowledge.service.RuleMarkdownService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Hand-rolled MCP over JSON-RPC 2.0.
 *
 * <p>No SDK on purpose: the protocol surface this server needs is initialize +
 * tools + resources + prompts, the wire format is newline-delimited JSON, and
 * owning it outright means the same dispatcher serves both stdio and HTTP with no
 * transport-specific glue.
 *
 * <p>Transport-agnostic by design: {@link #handle(JsonNode)} takes one parsed
 * message and returns one response node, or {@code null} for a notification.
 */
@Component
public class McpServer {

    /** Newest first. An unknown version from a client is answered with our newest. */
    private static final List<String> SUPPORTED_PROTOCOLS = List.of("2025-06-18", "2025-03-26", "2024-11-05");

    private static final String CONTEXT_URI = "knowledge://context";
    private static final String RULES_URI_PREFIX = "knowledge://rules/";
    private static final String CONTEXT_PROMPT = "knowledge-context";

    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;

    private static final Logger log = LoggerFactory.getLogger(McpServer.class);

    private final ObjectMapper mapper;
    private final KnowledgeToolCatalog catalog;
    private final ContextBundleService bundleService;
    private final RuleMarkdownService markdownService;
    private final KnowledgeProperties properties;

    public McpServer(ObjectMapper mapper,
                     KnowledgeToolCatalog catalog,
                     ContextBundleService bundleService,
                     RuleMarkdownService markdownService,
                     KnowledgeProperties properties) {
        this.mapper = mapper;
        this.catalog = catalog;
        this.bundleService = bundleService;
        this.markdownService = markdownService;
        this.properties = properties;
    }

    /**
     * Parse one line of newline-delimited JSON, dispatch it, and serialise the reply.
     *
     * @return the response as JSON text, or {@code null} when nothing should be sent
     *         (notifications, and blank lines)
     */
    public String handleLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        JsonNode message;
        try {
            message = mapper.readTree(line);
        } catch (JsonProcessingException e) {
            return write(error(null, PARSE_ERROR, "invalid JSON: " + e.getOriginalMessage()));
        }
        JsonNode response = handle(message);
        return response == null ? null : write(response);
    }

    /** @return the response node, or {@code null} for a notification. */
    public JsonNode handle(JsonNode message) {
        if (message == null || !message.isObject()) {
            return error(null, INVALID_REQUEST, "request must be a JSON object");
        }
        JsonNode id = message.get("id");
        String method = message.path("method").asText(null);
        if (method == null || method.isBlank()) {
            return id == null ? null : error(id, INVALID_REQUEST, "missing method");
        }
        JsonNode params = message.get("params");
        if (params == null || !params.isObject()) {
            params = mapper.createObjectNode();
        }

        // Notifications carry no id and must never be answered.
        boolean notification = id == null;
        try {
            JsonNode result = dispatch(method, params, notification);
            if (notification || result == null) {
                return null;
            }
            ObjectNode response = mapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", id);
            response.set("result", result);
            return response;
        } catch (UnknownMethodException e) {
            return notification ? null : error(id, METHOD_NOT_FOUND, "unknown method: " + method);
        } catch (IllegalArgumentException e) {
            return notification ? null : error(id, INVALID_PARAMS, String.valueOf(e.getMessage()));
        } catch (RuntimeException e) {
            log.warn("method {} failed", method, e);
            return notification ? null : error(id, INTERNAL_ERROR, e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    private JsonNode dispatch(String method, JsonNode params, boolean notification) {
        return switch (method) {
            case "initialize" -> initialize(params);
            case "ping" -> mapper.createObjectNode();
            case "tools/list" -> toolsList();
            case "tools/call" -> toolsCall(params);
            case "resources/list" -> resourcesList();
            case "resources/templates/list" -> wrap("resourceTemplates", mapper.createArrayNode());
            case "resources/read" -> resourcesRead(params);
            case "prompts/list" -> promptsList();
            case "prompts/get" -> promptsGet(params);
            case "logging/setLevel" -> mapper.createObjectNode();
            default -> {
                if (method.startsWith("notifications/")) {
                    // initialized / cancelled / progress: acknowledged by doing nothing.
                    yield null;
                }
                if (notification) {
                    yield null;
                }
                throw new UnknownMethodException();
            }
        };
    }

    /** {@code {"<field>": <value>}} - written out rather than chaining set(), whose
     * return type is generic and depends on inference at every call site. */
    private ObjectNode wrap(String field, JsonNode value) {
        ObjectNode node = mapper.createObjectNode();
        node.set(field, value);
        return node;
    }

    // --------------------------------------------------------------- initialize

    private JsonNode initialize(JsonNode params) {
        String requested = params.path("protocolVersion").asText(null);
        String negotiated = requested != null && SUPPORTED_PROTOCOLS.contains(requested)
                ? requested
                : SUPPORTED_PROTOCOLS.get(0);

        ObjectNode capabilities = mapper.createObjectNode();
        capabilities.putObject("tools").put("listChanged", false);
        ObjectNode resources = capabilities.putObject("resources");
        resources.put("subscribe", false);
        resources.put("listChanged", false);
        capabilities.putObject("prompts").put("listChanged", false);
        capabilities.putObject("logging");

        ObjectNode serverInfo = mapper.createObjectNode();
        serverInfo.put("name", properties.serverName());
        serverInfo.put("title", "Knowledge Sharing MCP");
        serverInfo.put("version", properties.serverVersion());

        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", negotiated);
        result.set("capabilities", capabilities);
        result.set("serverInfo", serverInfo);
        result.put("instructions", instructions());
        return result;
    }

    /**
     * Server instructions. This is the automatic half of the design: a client that
     * surfaces {@code instructions} gets the accumulated rules in its system context
     * with no tool call, so knowledge applies even when the model never thinks to ask
     * for it.
     */
    private String instructions() {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                This server holds the durable rules learned from working with this user - preferences, \
                conventions, pitfalls, project facts - each backed by how many times it was observed.

                How to use it:
                1. At the start of a task call `knowledge_context` (add a `query` for a specific topic) \
                and follow what it returns. It is evidence, not guesswork.
                2. Whenever the user corrects you, says "always"/"never"/"다음부터는", or you discover a \
                non-obvious fact about the project, call `knowledge_observe` with the rule in imperative \
                form plus what NOT to do. Repeats are merged; a rule is promoted automatically once it has \
                been seen enough times.
                3. Only the user can bless a rule outright (`knowledge_confirm`) or retire one \
                (`knowledge_archive`). Never archive a rule because it is inconvenient.
                4. Do not record one-off task facts. Record only what would change your behaviour in a \
                future, unrelated session.
                """);
        if (properties.inject().onInitialize()) {
            String bundle = bundleService.markdownFor(properties.defaultProjectKey(), properties.inject().maxChars());
            sb.append("\n--- CURRENT RULES (auto-injected) ---\n").append(bundle);
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------- tools

    private JsonNode toolsList() {
        ArrayNode array = mapper.createArrayNode();
        for (McpTool tool : catalog.all()) {
            ObjectNode node = mapper.createObjectNode();
            node.put("name", tool.name());
            node.put("title", tool.title());
            node.put("description", tool.description());
            node.set("inputSchema", parseSchema(tool));
            array.add(node);
        }
        return wrap("tools", array);
    }

    private JsonNode toolsCall(JsonNode params) {
        String name = params.path("name").asText(null);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tools/call requires a tool name");
        }
        McpTool tool = catalog.find(name)
                .orElseThrow(() -> new IllegalArgumentException("unknown tool: " + name));

        JsonNode arguments = params.get("arguments");
        if (arguments == null || !arguments.isObject()) {
            arguments = mapper.createObjectNode();
        }

        ToolResult result;
        try {
            result = tool.handler().apply(arguments);
        } catch (RuntimeException e) {
            // Per MCP, a tool that fails reports isError in the RESULT - a JSON-RPC
            // error would hide the reason from the model that must fix its call.
            log.info("tool {} rejected the call: {}", name, e.getMessage());
            result = ToolResult.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        ObjectNode content = mapper.createObjectNode();
        content.put("type", "text");
        content.put("text", result.text() == null ? "" : result.text());

        ObjectNode response = mapper.createObjectNode();
        response.set("content", mapper.createArrayNode().add(content));
        response.put("isError", result.isError());
        if (result.structured() != null && !result.structured().isEmpty()) {
            response.set("structuredContent", mapper.valueToTree(result.structured()));
        }
        return response;
    }

    private JsonNode parseSchema(McpTool tool) {
        try {
            return mapper.readTree(tool.inputSchemaJson());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("tool " + tool.name() + " has an invalid input schema", e);
        }
    }

    // ---------------------------------------------------------------- resources

    private JsonNode resourcesList() {
        ArrayNode array = mapper.createArrayNode();
        ObjectNode context = mapper.createObjectNode();
        context.put("uri", CONTEXT_URI);
        context.put("name", "agent-context");
        context.put("title", "Agent Knowledge Context");
        context.put("description", "모든 ACTIVE 규칙을 점수 순으로 묶은 주입용 마크다운 번들");
        context.put("mimeType", "text/markdown");
        array.add(context);

        for (PatternCategory category : PatternCategory.values()) {
            ObjectNode node = mapper.createObjectNode();
            node.put("uri", RULES_URI_PREFIX + category.fileName());
            node.put("name", category.fileName());
            node.put("title", category.heading());
            node.put("description", category.hint());
            node.put("mimeType", "text/markdown");
            array.add(node);
        }
        return wrap("resources", array);
    }

    private JsonNode resourcesRead(JsonNode params) {
        String uri = params.path("uri").asText(null);
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("resources/read requires a uri");
        }
        String text;
        if (CONTEXT_URI.equals(uri)) {
            text = bundleService.markdownFor(properties.defaultProjectKey(), properties.bundle().maxChars());
        } else if (uri.startsWith(RULES_URI_PREFIX)) {
            String fileName = uri.substring(RULES_URI_PREFIX.length());
            PatternCategory category = PatternCategory.parse(fileName);
            text = markdownService.readRuleFile(category);
        } else {
            throw new IllegalArgumentException("unknown resource uri: " + uri);
        }

        ObjectNode contents = mapper.createObjectNode();
        contents.put("uri", uri);
        contents.put("mimeType", "text/markdown");
        contents.put("text", text);
        return wrap("contents", mapper.createArrayNode().add(contents));
    }

    // ------------------------------------------------------------------ prompts

    private JsonNode promptsList() {
        ObjectNode argProject = mapper.createObjectNode();
        argProject.put("name", "projectKey");
        argProject.put("description", "이 프로젝트 전용 규칙까지 포함");
        argProject.put("required", false);

        ObjectNode argQuery = mapper.createObjectNode();
        argQuery.put("name", "query");
        argQuery.put("description", "특정 주제의 규칙만");
        argQuery.put("required", false);

        ObjectNode prompt = mapper.createObjectNode();
        prompt.put("name", CONTEXT_PROMPT);
        prompt.put("title", "Inject knowledge rules");
        prompt.put("description", "축적된 규칙을 대화에 주입한다");
        prompt.set("arguments", mapper.createArrayNode().add(argProject).add(argQuery));

        return wrap("prompts", mapper.createArrayNode().add(prompt));
    }

    private JsonNode promptsGet(JsonNode params) {
        String name = params.path("name").asText(null);
        if (!CONTEXT_PROMPT.equals(name)) {
            throw new IllegalArgumentException("unknown prompt: " + name);
        }
        JsonNode arguments = params.path("arguments");
        String projectKey = arguments.path("projectKey").asText(properties.defaultProjectKey());
        String query = arguments.path("query").asText(null);

        ContextBundleService.Bundle bundle = bundleService.build(new ContextBundleService.BundleRequest(
                projectKey, java.util.Set.of(), query, properties.bundle().maxChars(), false));

        ObjectNode content = mapper.createObjectNode();
        content.put("type", "text");
        content.put("text", bundle.markdown());

        ObjectNode message = mapper.createObjectNode();
        message.put("role", "user");
        message.set("content", content);

        ObjectNode result = mapper.createObjectNode();
        result.put("description", "knowledge-mcp: %d rule(s)".formatted(bundle.included()));
        result.set("messages", mapper.createArrayNode().add(message));
        return result;
    }

    // ------------------------------------------------------------------- errors

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode error = mapper.createObjectNode();
        error.put("code", code);
        error.put("message", message == null ? "error" : message);

        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id == null || id.isNull()) {
            response.putNull("id");
        } else {
            response.set("id", id);
        }
        response.set("error", error);
        return response;
    }

    private String write(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise MCP response", e);
        }
    }

    /** Distinguishes "no such method" from "bad arguments" without string matching. */
    private static final class UnknownMethodException extends RuntimeException {
        UnknownMethodException() {
            super(null, null, false, false);
        }
    }
}
