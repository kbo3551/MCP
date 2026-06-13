package dev.codex.dbmcp.domain.policy;

public interface ReadQueryDialect {

    String applyLimit(String sql, int limit);
}
