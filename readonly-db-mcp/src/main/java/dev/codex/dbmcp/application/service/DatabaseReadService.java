package dev.codex.dbmcp.application.service;

import dev.codex.dbmcp.application.port.in.DatabaseReadUseCase;
import dev.codex.dbmcp.application.port.out.DatabaseReadRepository;
import dev.codex.dbmcp.domain.model.ColumnDescription;
import dev.codex.dbmcp.domain.model.QueryResult;
import dev.codex.dbmcp.domain.model.TableSummary;
import dev.codex.dbmcp.domain.policy.OwnerAccessPolicy;
import dev.codex.dbmcp.domain.policy.QueryLimitAppender;
import dev.codex.dbmcp.domain.policy.SqlGuard;
import dev.codex.dbmcp.domain.service.MaskingService;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DatabaseReadService implements DatabaseReadUseCase {

    private final DatabaseReadRepository repository;
    private final OwnerAccessPolicy ownerAccessPolicy;
    private final SqlGuard sqlGuard;
    private final QueryLimitAppender queryLimitAppender;
    private final MaskingService maskingService;

    public DatabaseReadService(
            DatabaseReadRepository repository,
            OwnerAccessPolicy ownerAccessPolicy,
            SqlGuard sqlGuard,
            QueryLimitAppender queryLimitAppender,
            MaskingService maskingService) {
        this.repository = repository;
        this.ownerAccessPolicy = ownerAccessPolicy;
        this.sqlGuard = sqlGuard;
        this.queryLimitAppender = queryLimitAppender;
        this.maskingService = maskingService;
    }

    @Override
    public List<TableSummary> listTables(String owner) {
        return repository.findTables(ownerAccessPolicy.requireAllowed(owner));
    }

    @Override
    public List<ColumnDescription> describeTable(String owner, String tableName) {
        return repository.findColumns(
                ownerAccessPolicy.requireConcreteOwner(owner),
                ownerAccessPolicy.requireValidTableName(tableName));
    }

    @Override
    public List<TableSummary> searchTables(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("keyword must not be blank");
        }
        return repository.searchTables(
                ownerAccessPolicy.allowedOwners(),
                ownerAccessPolicy.allOwnersAllowed(),
                keyword.strip());
    }

    @Override
    public QueryResult runSelectQuery(String sql, Integer limit) {
        String validatedSql = sqlGuard.validate(sql);
        QueryLimitAppender.LimitedQuery limitedQuery =
                queryLimitAppender.append(validatedSql, limit);
        QueryResult result =
                repository.executeSelect(limitedQuery.sql(), limitedQuery.appliedLimit());
        return new QueryResult(
                result.columns(),
                maskingService.mask(result.rows()),
                result.rowCount(),
                result.appliedLimit());
    }
}
