package dev.codex.dbmcp.infrastructure.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "db-mcp")
public record DbMcpProperties(
        @NotEmpty List<String> allowedOwners,
        @Min(1) @Max(500) int defaultLimit,
        @Min(1) @Max(500) int maxLimit,
        @Min(1) int queryTimeoutSeconds) {

    public DbMcpProperties {
        allowedOwners = allowedOwners == null ? List.of() : List.copyOf(allowedOwners);
        if (defaultLimit > maxLimit) {
            throw new IllegalArgumentException("defaultLimit must be <= maxLimit");
        }
    }
}
