package dev.codex.dbmcp.infrastructure.oracle;

import dev.codex.dbmcp.application.port.out.DatabaseReadRepository;
import dev.codex.dbmcp.domain.model.ColumnDescription;
import dev.codex.dbmcp.domain.model.QueryResult;
import dev.codex.dbmcp.domain.model.TableSummary;
import dev.codex.dbmcp.infrastructure.config.DbMcpProperties;
import java.io.IOException;
import java.io.Reader;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOracleReadRepository implements DatabaseReadRepository {

    private static final String LIST_TABLES_SQL = """
            SELECT t.owner, t.table_name, c.comments
            FROM all_tables t
            LEFT JOIN all_tab_comments c
              ON c.owner = t.owner
             AND c.table_name = t.table_name
             AND c.table_type = 'TABLE'
            WHERE t.owner = ?
            ORDER BY t.table_name
            """;

    private static final String LIST_ALL_TABLES_SQL = """
            SELECT t.owner, t.table_name, c.comments
            FROM all_tables t
            LEFT JOIN all_tab_comments c
              ON c.owner = t.owner
             AND c.table_name = t.table_name
             AND c.table_type = 'TABLE'
            ORDER BY t.owner, t.table_name
            """;

    private static final String DESCRIBE_TABLE_SQL = """
            SELECT c.owner,
                   c.table_name,
                   c.column_name,
                   c.data_type,
                   c.data_length,
                   c.data_precision,
                   c.data_scale,
                   c.nullable,
                   c.column_id,
                   cc.comments
            FROM all_tab_columns c
            LEFT JOIN all_col_comments cc
              ON cc.owner = c.owner
             AND cc.table_name = c.table_name
             AND cc.column_name = c.column_name
            WHERE c.owner = ?
              AND c.table_name = ?
            ORDER BY c.column_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final int queryTimeoutSeconds;

    public JdbcOracleReadRepository(
            JdbcTemplate jdbcTemplate,
            DbMcpProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.queryTimeoutSeconds = properties.queryTimeoutSeconds();
    }

    @Override
    public List<TableSummary> findTables(String owner) {
        return executeReadOnly(connection -> {
            boolean allOwners = "*".equals(owner);
            String sql = allOwners ? LIST_ALL_TABLES_SQL : LIST_TABLES_SQL;
            try (PreparedStatement statement = prepare(connection, sql)) {
                if (!allOwners) {
                    statement.setString(1, owner);
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<TableSummary> tables = new ArrayList<>();
                    while (resultSet.next()) {
                        tables.add(new TableSummary(
                                resultSet.getString("owner"),
                                resultSet.getString("table_name"),
                                resultSet.getString("comments")));
                    }
                    return tables;
                }
            }
        });
    }

    @Override
    public List<ColumnDescription> findColumns(String owner, String tableName) {
        return executeReadOnly(connection -> {
            try (PreparedStatement statement = prepare(connection, DESCRIBE_TABLE_SQL)) {
                statement.setString(1, owner);
                statement.setString(2, tableName);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<ColumnDescription> columns = new ArrayList<>();
                    while (resultSet.next()) {
                        columns.add(new ColumnDescription(
                                resultSet.getString("owner"),
                                resultSet.getString("table_name"),
                                resultSet.getString("column_name"),
                                resultSet.getString("data_type"),
                                getNullableInteger(resultSet, "data_length"),
                                getNullableInteger(resultSet, "data_precision"),
                                getNullableInteger(resultSet, "data_scale"),
                                "Y".equals(resultSet.getString("nullable")),
                                resultSet.getInt("column_id"),
                                resultSet.getString("comments")));
                    }
                    return columns;
                }
            }
        });
    }

    @Override
    public List<TableSummary> searchTables(
            Set<String> owners,
            boolean allOwnersAllowed,
            String keyword) {
        StringJoiner placeholders = new StringJoiner(", ");
        owners.forEach(ignored -> placeholders.add("?"));
        String ownerCondition =
                allOwnersAllowed ? "" : "t.owner IN (" + placeholders + ") AND";
        String sql = """
                SELECT t.owner, t.table_name, c.comments
                FROM all_tables t
                LEFT JOIN all_tab_comments c
                  ON c.owner = t.owner
                 AND c.table_name = t.table_name
                 AND c.table_type = 'TABLE'
                WHERE %s
                  (
                    UPPER(t.table_name) LIKE ? ESCAPE '\\'
                    OR UPPER(NVL(c.comments, '')) LIKE ? ESCAPE '\\'
                  )
                ORDER BY t.owner, t.table_name
                """.formatted(ownerCondition);
        String pattern = "%" + escapeLike(keyword.toUpperCase(Locale.ROOT)) + "%";

        return executeReadOnly(connection -> {
            try (PreparedStatement statement = prepare(connection, sql)) {
                int parameterIndex = 1;
                if (!allOwnersAllowed) {
                    for (String owner : owners) {
                        statement.setString(parameterIndex++, owner);
                    }
                }
                statement.setString(parameterIndex++, pattern);
                statement.setString(parameterIndex, pattern);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<TableSummary> tables = new ArrayList<>();
                    while (resultSet.next()) {
                        tables.add(new TableSummary(
                                resultSet.getString("owner"),
                                resultSet.getString("table_name"),
                                resultSet.getString("comments")));
                    }
                    return tables;
                }
            }
        });
    }

    @Override
    public QueryResult executeSelect(String wrappedSql, int appliedLimit) {
        return executeReadOnly(connection -> {
            try (PreparedStatement statement = prepare(connection, wrappedSql);
                    ResultSet resultSet = statement.executeQuery()) {
                ResultSetMetaData metadata = resultSet.getMetaData();
                List<String> columns = uniqueColumnLabels(metadata);
                List<Map<String, Object>> rows = new ArrayList<>();

                while (resultSet.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int index = 1; index <= metadata.getColumnCount(); index++) {
                        row.put(columns.get(index - 1), readValue(resultSet, index));
                    }
                    rows.add(row);
                }
                return new QueryResult(columns, rows, rows.size(), appliedLimit);
            }
        });
    }

    private <T> T executeReadOnly(SqlWork<T> work) {
        return jdbcTemplate.execute((ConnectionCallback<T>) connection -> {
            boolean originalReadOnly = connection.isReadOnly();
            connection.setReadOnly(true);
            try {
                return work.execute(connection);
            } finally {
                if (!originalReadOnly) {
                    connection.setReadOnly(false);
                }
            }
        });
    }

    private PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(queryTimeoutSeconds);
        statement.setFetchSize(100);
        return statement;
    }

    private List<String> uniqueColumnLabels(ResultSetMetaData metadata) throws SQLException {
        List<String> labels = new ArrayList<>();
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            String label = metadata.getColumnLabel(index);
            int occurrence = occurrences.merge(label, 1, Integer::sum);
            labels.add(occurrence == 1 ? label : label + "_" + occurrence);
        }
        return labels;
    }

    private Object readValue(ResultSet resultSet, int index) throws SQLException {
        Object value = resultSet.getObject(index);
        if (value instanceof Clob clob) {
            return readClob(clob);
        }
        if (value instanceof Blob blob) {
            return "[BINARY " + blob.length() + " bytes]";
        }
        if (value instanceof SQLXML sqlxml) {
            return sqlxml.getString();
        }
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof java.time.temporal.TemporalAccessor
                || value instanceof java.util.Date) {
            return value;
        }
        return value.toString();
    }

    private String readClob(Clob clob) throws SQLException {
        try (Reader reader = clob.getCharacterStream()) {
            char[] buffer = new char[4096];
            int length = reader.read(buffer);
            if (length < 0) {
                return "";
            }
            return new String(buffer, 0, length)
                    + (clob.length() > length ? "...[TRUNCATED]" : "");
        } catch (IOException exception) {
            throw new SQLException("Failed to read CLOB", exception);
        }
    }

    private Integer getNullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T execute(Connection connection) throws SQLException;
    }
}
