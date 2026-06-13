package dev.codex.dbmcp.domain.policy;

public class QueryLimitAppender {

    private final int defaultLimit;
    private final int maxLimit;
    private final ReadQueryDialect queryDialect;

    public QueryLimitAppender(
            int defaultLimit,
            int maxLimit,
            ReadQueryDialect queryDialect) {
        if (defaultLimit < 1) {
            throw new IllegalArgumentException("defaultLimit must be positive");
        }
        if (maxLimit < defaultLimit) {
            throw new IllegalArgumentException("maxLimit must be >= defaultLimit");
        }
        this.defaultLimit = defaultLimit;
        this.maxLimit = maxLimit;
        this.queryDialect = queryDialect;
    }

    public LimitedQuery append(String sql, Integer requestedLimit) {
        int appliedLimit = requestedLimit == null ? defaultLimit : requestedLimit;
        if (appliedLimit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        appliedLimit = Math.min(appliedLimit, maxLimit);

        return new LimitedQuery(queryDialect.applyLimit(sql, appliedLimit), appliedLimit);
    }

    public record LimitedQuery(String sql, int appliedLimit) {
    }
}
