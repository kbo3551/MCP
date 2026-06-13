package dev.codex.dbmcp.infrastructure.oracle;

import dev.codex.dbmcp.domain.policy.ReadQueryDialect;
import org.springframework.stereotype.Component;

@Component
public class OracleReadQueryDialect implements ReadQueryDialect {

    @Override
    public String applyLimit(String sql, int limit) {
        return """
                SELECT *
                FROM (
                    %s
                )
                WHERE ROWNUM <= %d
                """.formatted(sql.strip(), limit).strip();
    }
}
