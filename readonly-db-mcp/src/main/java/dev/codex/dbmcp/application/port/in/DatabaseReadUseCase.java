package dev.codex.dbmcp.application.port.in;

import dev.codex.dbmcp.domain.model.ColumnDescription;
import dev.codex.dbmcp.domain.model.QueryResult;
import dev.codex.dbmcp.domain.model.TableSummary;
import java.util.List;

public interface DatabaseReadUseCase {

    List<TableSummary> listTables(String owner);

    List<ColumnDescription> describeTable(String owner, String tableName);

    List<TableSummary> searchTables(String keyword);

    QueryResult runSelectQuery(String sql, Integer limit);
}
