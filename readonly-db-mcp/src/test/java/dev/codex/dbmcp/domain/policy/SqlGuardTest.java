package dev.codex.dbmcp.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.codex.dbmcp.domain.exception.UnsafeSqlException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SqlGuardTest {

    private final SqlGuard sqlGuard = new SqlGuard();

    @Test
    void acceptsSelectAndWithQueries() {
        assertThat(sqlGuard.validate(" SELECT * FROM app.users "))
                .isEqualTo("SELECT * FROM app.users");
        assertThat(sqlGuard.validate(
                        "WITH recent AS (SELECT id FROM app.orders) SELECT * FROM recent"))
                .startsWith("WITH recent");
    }

    @Test
    void ignoresBlockedWordsInsideStringLiterals() {
        assertThat(sqlGuard.validate("SELECT 'delete is text' FROM dual"))
                .isEqualTo("SELECT 'delete is text' FROM dual");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsafeSql")
    void rejectsUnsafeSql(String reason, String sql) {
        assertThatThrownBy(() -> sqlGuard.validate(sql))
                .as(reason)
                .isInstanceOf(UnsafeSqlException.class);
    }

    private static Stream<Arguments> unsafeSql() {
        return Stream.of(
                Arguments.of("DML", "UPDATE users SET name = 'x'"),
                Arguments.of("DML hidden in CTE", "WITH x AS (DELETE FROM users) SELECT * FROM x"),
                Arguments.of("DDL", "SELECT 1 FROM dual CREATE TABLE x(id NUMBER)"),
                Arguments.of("transaction", "SELECT 1 FROM dual COMMIT"),
                Arguments.of("PL/SQL", "BEGIN NULL END"),
                Arguments.of("dangerous package", "SELECT DBMS_RANDOM.VALUE FROM dual"),
                Arguments.of("UTL package", "SELECT UTL_HTTP.REQUEST('https://example.com') FROM dual"),
                Arguments.of("for update", "SELECT * FROM users FOR UPDATE"),
                Arguments.of("semicolon", "SELECT * FROM users; DELETE FROM users"),
                Arguments.of("semicolon in literal", "SELECT ';' FROM dual"),
                Arguments.of("line comment", "SELECT * FROM users -- comment"),
                Arguments.of("block comment obfuscation", "SEL/**/ECT * FROM users"),
                Arguments.of(
                        "quoted package bypass",
                        "SELECT \"DBMS_SQL\".\"OPEN_CURSOR\" FROM dual"),
                Arguments.of("non-select CTE", "WITH x AS (SELECT 1 FROM dual) UPDATE users SET id = 1"));
    }
}
