package dev.codex.dbmcp.infrastructure.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codex.dbmcp.application.port.out.AuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class FileAuditLogger implements AuditLogger {

    private static final Logger AUDIT_LOGGER =
            LoggerFactory.getLogger("DB_MCP_AUDIT");

    private final ObjectMapper objectMapper;

    public FileAuditLogger() {
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @Override
    public void log(AuditEvent event) {
        try {
            AUDIT_LOGGER.info(objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException exception) {
            AUDIT_LOGGER.error(
                    "Failed to serialize audit event for tool={}",
                    event.toolName(),
                    exception);
        }
    }
}
