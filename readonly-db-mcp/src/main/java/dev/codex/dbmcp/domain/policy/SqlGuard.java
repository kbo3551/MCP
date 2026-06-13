package dev.codex.dbmcp.domain.policy;

import dev.codex.dbmcp.domain.exception.UnsafeSqlException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class SqlGuard {

    private static final Set<String> ALLOWED_FIRST_TOKENS = Set.of("SELECT", "WITH");
    private static final Set<String> BLOCKED_TOKENS = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE",
            "DROP", "ALTER", "TRUNCATE", "CREATE",
            "GRANT", "REVOKE", "COMMIT", "ROLLBACK",
            "EXEC", "EXECUTE", "CALL", "BEGIN", "DECLARE",
            "JAVA", "DIRECTORY");

    public String validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new UnsafeSqlException("SQL must not be blank");
        }
        if (sql.indexOf(';') >= 0) {
            throw new UnsafeSqlException("Semicolons and multiple statements are not allowed");
        }

        List<String> tokens = tokenize(sql);
        if (tokens.isEmpty() || !ALLOWED_FIRST_TOKENS.contains(tokens.get(0))) {
            throw new UnsafeSqlException("Only SELECT or WITH queries are allowed");
        }
        if (tokens.get(0).equals("WITH") && !tokens.contains("SELECT")) {
            throw new UnsafeSqlException("WITH query must contain SELECT");
        }

        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (BLOCKED_TOKENS.contains(token)
                    || token.startsWith("DBMS_")
                    || token.startsWith("UTL_")) {
                throw new UnsafeSqlException("Blocked SQL token: " + token);
            }
            if ("FOR".equals(token)
                    && index + 1 < tokens.size()
                    && "UPDATE".equals(tokens.get(index + 1))) {
                throw new UnsafeSqlException("FOR UPDATE is not allowed");
            }
        }

        return sql.strip();
    }

    private List<String> tokenize(String sql) {
        List<String> tokens = new ArrayList<>();
        int index = 0;

        while (index < sql.length()) {
            char current = sql.charAt(index);

            if (current == '-' && hasNext(sql, index, '-')
                    || current == '/' && hasNext(sql, index, '*')) {
                throw new UnsafeSqlException("SQL comments are not allowed");
            }
            if (Character.isWhitespace(current) || isPunctuation(current)) {
                index++;
                continue;
            }
            if (current == '\'') {
                index = skipSingleQuotedLiteral(sql, index + 1);
                continue;
            }
            if (current == '"') {
                throw new UnsafeSqlException("Quoted identifiers are not allowed");
            }
            if (Character.isLetter(current) || current == '_') {
                int start = index++;
                while (index < sql.length()) {
                    char candidate = sql.charAt(index);
                    if (!Character.isLetterOrDigit(candidate)
                            && candidate != '_'
                            && candidate != '$'
                            && candidate != '#') {
                        break;
                    }
                    index++;
                }
                tokens.add(sql.substring(start, index).toUpperCase(Locale.ROOT));
                continue;
            }
            index++;
        }
        return tokens;
    }

    private int skipSingleQuotedLiteral(String sql, int index) {
        while (index < sql.length()) {
            if (sql.charAt(index) == '\'') {
                if (index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                    index += 2;
                    continue;
                }
                return index + 1;
            }
            index++;
        }
        throw new UnsafeSqlException("Unterminated string literal");
    }

    private boolean hasNext(String sql, int index, char expected) {
        return index + 1 < sql.length() && sql.charAt(index + 1) == expected;
    }

    private boolean isPunctuation(char value) {
        return "(),.*+-%<>=!|:^?".indexOf(value) >= 0;
    }
}
