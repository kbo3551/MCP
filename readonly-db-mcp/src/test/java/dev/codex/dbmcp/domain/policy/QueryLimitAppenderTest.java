package dev.codex.dbmcp.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.codex.dbmcp.infrastructure.oracle.OracleReadQueryDialect;
import org.junit.jupiter.api.Test;

class QueryLimitAppenderTest {

    private final QueryLimitAppender appender =
            new QueryLimitAppender(100, 500, new OracleReadQueryDialect());

    @Test
    void appliesDefaultLimitAndWrapsQuery() {
        QueryLimitAppender.LimitedQuery result =
                appender.append("SELECT * FROM users", null);

        assertThat(result.appliedLimit()).isEqualTo(100);
        assertThat(result.sql()).isEqualTo("""
                SELECT *
                FROM (
                    SELECT * FROM users
                )
                WHERE ROWNUM <= 100""");
    }

    @Test
    void capsLimitAtMaximum() {
        QueryLimitAppender.LimitedQuery result =
                appender.append("SELECT * FROM users", 1000);

        assertThat(result.appliedLimit()).isEqualTo(500);
        assertThat(result.sql()).endsWith("WHERE ROWNUM <= 500");
    }

    @Test
    void rejectsNonPositiveLimit() {
        assertThatThrownBy(() -> appender.append("SELECT 1 FROM dual", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
