package kr.co.page1.knowledge.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import kr.co.page1.knowledge.mcp.McpServer;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP over HTTP: one endpoint, same dispatcher as stdio.
 *
 * <p>A notification carries no id and gets {@code 202 Accepted} with no body, which
 * is what the streamable-HTTP transport expects. No SSE stream is opened - this
 * server never initiates server-to-client requests, so there is nothing to stream.
 */
@RestController
public class McpHttpController {

    private final McpServer server;
    private final ObjectMapper mapper;

    public McpHttpController(McpServer server, ObjectMapper mapper) {
        this.server = server;
        this.mapper = mapper;
    }

    @PostMapping(path = "/mcp", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> handle(@RequestBody JsonNode body) {
        if (body != null && body.isArray()) {
            ArrayNode responses = mapper.createArrayNode();
            body.forEach(message -> {
                JsonNode response = server.handle(message);
                if (response != null) {
                    responses.add(response);
                }
            });
            return responses.isEmpty()
                    ? ResponseEntity.accepted().build()
                    : ResponseEntity.ok(responses);
        }

        JsonNode response = server.handle(body);
        return response == null
                ? ResponseEntity.accepted().build()
                : ResponseEntity.ok(response);
    }
}
