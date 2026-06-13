package dev.codex.dbmcp.application.port.out;

import dev.codex.dbmcp.domain.model.ColumnDescription;
import dev.codex.dbmcp.domain.model.QueryResult;
import dev.codex.dbmcp.domain.model.TableSummary;
import java.util.List;
import java.util.Set;

public interface DatabaseReadRepository {

    List<TableSummary> findTables(String owner);

    List<ColumnDescription> findColumns(String owner, String tableName);

    List<TableSummary> searchTables(
            Set<String> owners,
            boolean allOwnersAllowed,
            String keyword);

    QueryResult executeSelect(String wrappedSql, int appliedLimit);
}
