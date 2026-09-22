package kr.co.page1.knowledge.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Drives the dispatcher the way a client does - one JSON line in, one JSON line out -
 * so the test covers exactly what both transports hand to it.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mcp-protocol-test;DB_CLOSE_DELAY=-1",
        "knowledge.sync.on-write=false",
        "knowledge.inject.on-initialize=false"
})
class McpServerProtocolTest {

    @Autowired
    private McpServer server;

    @Autowired
    private ObjectMapper mapper;

    private JsonNode call(String json) throws Exception {
        String response = server.handleLine(json);
        assertThat(response).as("expected a response for %s", json).isNotNull();
        return mapper.readTree(response);
    }

    @Test
    @DisplayName("initialize 는 클라이언트가 요청한 프로토콜 버전을 그대로 수락한다")
    void initializeNegotiatesProtocol() throws Exception {
        JsonNode result = call("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2024-11-05",
                  "capabilities":{},
                  "clientInfo":{"name":"test","version":"1"}}}
                """).path("result");

        assertThat(result.path("protocolVersion").asText()).isEqualTo("2024-11-05");
        assertThat(result.path("serverInfo").path("name").asText()).isEqualTo("knowledge-mcp");
        assertThat(result.path("capabilities").has("tools")).isTrue();
        assertThat(result.path("instructions").asText()).contains("knowledge_observe");
    }

    @Test
    @DisplayName("모르는 프로토콜 버전은 서버의 최신 버전으로 답한다")
    void initializeFallsBackForUnknownProtocol() throws Exception {
        JsonNode result = call("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}
                """).path("result");

        assertThat(result.path("protocolVersion").asText()).isEqualTo("2025-06-18");
    }

    @Test
    @DisplayName("notification 에는 응답하지 않는다")
    void notificationsProduceNoResponse() {
        assertThat(server.handleLine("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).isNull();
    }

    @Test
    void toolsListExposesTheKnowledgeTools() throws Exception {
        JsonNode tools = call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")
                .path("result").path("tools");

        assertThat(tools.isArray()).isTrue();
        List<String> names = new ArrayList<>();
        tools.forEach(tool -> {
            names.add(tool.path("name").asText());
            assertThat(tool.path("description").asText()).isNotBlank();
            assertThat(tool.path("inputSchema").path("type").asText()).isEqualTo("object");
        });
        assertThat(names).contains(
                "knowledge_observe", "knowledge_context", "knowledge_search",
                "knowledge_sync_rules", "knowledge_conflicts",
                "knowledge_inspect", "knowledge_review");
    }

    @Test
    @DisplayName("observe 로 만든 규칙을 inspect 하면 근거 로그까지 돌아온다")
    void inspectReturnsTheEvidenceLog() throws Exception {
        JsonNode created = call("""
                {"jsonrpc":"2.0","id":20,"method":"tools/call","params":{
                  "name":"knowledge_observe",
                  "arguments":{
                    "statement":"릴리스 노트는 한국어로 쓸 것",
                    "category":"PREFERENCE",
                    "note":"사용자가 영문 초안을 반려했다",
                    "author":"test"}}}
                """).path("result").path("structuredContent");

        long id = created.path("id").asLong();
        assertThat(id).isPositive();

        JsonNode inspected = call("""
                {"jsonrpc":"2.0","id":21,"method":"tools/call","params":{
                  "name":"knowledge_inspect","arguments":{"id":%d}}}
                """.formatted(id)).path("result");

        assertThat(inspected.path("isError").asBoolean()).isFalse();
        assertThat(inspected.path("structuredContent").path("evidence").get(0).path("note").asText())
                .isEqualTo("사용자가 영문 초안을 반려했다");
        assertThat(inspected.path("content").get(0).path("text").asText())
                .contains("릴리스 노트는 한국어로 쓸 것");
    }

    @Test
    @DisplayName("한 번에 너무 많은 패턴을 밀어넣으면 거부한다")
    void batchIsCapped() throws Exception {
        StringBuilder patterns = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            if (i > 0) {
                patterns.append(',');
            }
            patterns.append("{\"statement\":\"대량 투입 규칙 번호 ").append(i).append("\"}");
        }

        JsonNode result = call("""
                {"jsonrpc":"2.0","id":22,"method":"tools/call","params":{
                  "name":"knowledge_observe_batch","arguments":{"patterns":[%s]}}}
                """.formatted(patterns)).path("result");

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asText()).contains("limit is 20");
    }

    @Test
    @DisplayName("review 는 비어 있어도 정상 응답한다")
    void reviewRespondsOnAQuietStore() throws Exception {
        JsonNode result = call("""
                {"jsonrpc":"2.0","id":23,"method":"tools/call","params":{
                  "name":"knowledge_review","arguments":{"staleDays":365}}}
                """).path("result");

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("structuredContent").path("staleDays").asInt()).isEqualTo(365);
    }

    @Test
    @DisplayName("tools/call 로 관찰을 기록하면 structuredContent 로 상태가 돌아온다")
    void toolsCallRecordsAnObservation() throws Exception {
        JsonNode result = call("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                  "name":"knowledge_observe",
                  "arguments":{
                    "statement":"PR 제목은 70자 이내로 쓸 것",
                    "category":"CONVENTION",
                    "antiPattern":"제목에 상세 설명을 넣지 말 것",
                    "author":"test"}}}
                """).path("result");

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("content").get(0).path("text").asText()).contains("CREATED");
        assertThat(result.path("structuredContent").path("status").asText()).isEqualTo("OBSERVED");
        assertThat(result.path("structuredContent").path("category").asText()).isEqualTo("CONVENTION");
    }

    @Test
    @DisplayName("필수 인자가 없으면 JSON-RPC 에러가 아니라 isError 결과로 돌려준다")
    void toolFailureIsReportedInTheResult() throws Exception {
        JsonNode response = call("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{
                  "name":"knowledge_observe","arguments":{}}}
                """);

        assertThat(response.has("error")).isFalse();
        assertThat(response.path("result").path("isError").asBoolean()).isTrue();
        assertThat(response.path("result").path("content").get(0).path("text").asText())
                .contains("statement");
    }

    @Test
    void unknownToolIsAnInvalidParamsError() throws Exception {
        JsonNode response = call("""
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"nope"}}
                """);

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void unknownMethodIsMethodNotFound() throws Exception {
        JsonNode response = call("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"does/notExist\"}");

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32601);
    }

    @Test
    void malformedJsonIsAParseError() throws Exception {
        JsonNode response = mapper.readTree(server.handleLine("{not json"));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32700);
    }

    @Test
    @DisplayName("규칙 파일과 주입 번들이 resource 로 노출된다")
    void resourcesExposeContextAndRuleFiles() throws Exception {
        JsonNode resources = call("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"resources/list\"}")
                .path("result").path("resources");

        List<String> uris = new ArrayList<>();
        resources.forEach(resource -> uris.add(resource.path("uri").asText()));
        assertThat(uris).contains("knowledge://context", "knowledge://rules/preferences.md");

        JsonNode read = call("""
                {"jsonrpc":"2.0","id":8,"method":"resources/read","params":{"uri":"knowledge://context"}}
                """).path("result").path("contents").get(0);

        assertThat(read.path("mimeType").asText()).isEqualTo("text/markdown");
        assertThat(read.path("text").asText()).contains("Agent Knowledge Context");
    }

    @Test
    void promptReturnsTheBundleAsAUserMessage() throws Exception {
        JsonNode result = call("""
                {"jsonrpc":"2.0","id":9,"method":"prompts/get","params":{"name":"knowledge-context"}}
                """).path("result");

        assertThat(result.path("messages").get(0).path("role").asText()).isEqualTo("user");
        assertThat(result.path("messages").get(0).path("content").path("text").asText())
                .contains("Agent Knowledge Context");
    }
}
