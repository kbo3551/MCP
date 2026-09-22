package kr.co.page1.knowledge.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * A bad category name or a missing projectKey is the caller's mistake, not a server
 * fault - answer 400 with the reason so the caller can fix the call. The MCP surface
 * does its own error shaping (isError in the tool result), so this only covers REST.
 */
@RestControllerAdvice(basePackageClasses = KnowledgeRestController.class)
public class RestExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(body(e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalidBody(MethodArgumentNotValidException e) {
        StringBuilder message = new StringBuilder();
        e.getBindingResult().getFieldErrors().forEach(error ->
                message.append(error.getField()).append(' ').append(error.getDefaultMessage()).append("; "));
        return ResponseEntity.badRequest().body(body(message.toString().trim()));
    }

    private static Map<String, Object> body(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", message == null ? "bad request" : message);
        return out;
    }
}
