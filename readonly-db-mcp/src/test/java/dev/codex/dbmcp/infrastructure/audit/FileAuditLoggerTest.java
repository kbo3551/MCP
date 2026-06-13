package dev.codex.dbmcp.infrastructure.audit;

import static org.assertj.core.api.Assertions.assertThatCode;

import dev.codex.dbmcp.application.port.out.AuditLogger.AuditEvent;
import org.junit.jupiter.api.Test;

class FileAuditLoggerTest {

    @Test
    void createsWithoutSpringManagedObjectMapperAndSerializesAuditEvent() {
        FileAuditLogger logger = new FileAuditLogger();
        AuditEvent event =
                new AuditEvent("list_tables", "SELECT ...", null, true, 3, 12, null);

        assertThatCode(() -> logger.log(event)).doesNotThrowAnyException();
    }
}
