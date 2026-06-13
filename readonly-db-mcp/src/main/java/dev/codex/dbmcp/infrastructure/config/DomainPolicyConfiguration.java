package dev.codex.dbmcp.infrastructure.config;

import dev.codex.dbmcp.domain.policy.OwnerAccessPolicy;
import dev.codex.dbmcp.domain.policy.QueryLimitAppender;
import dev.codex.dbmcp.domain.policy.ReadQueryDialect;
import dev.codex.dbmcp.domain.policy.SqlGuard;
import dev.codex.dbmcp.domain.service.MaskingService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DomainPolicyConfiguration {

    @Bean
    OwnerAccessPolicy ownerAccessPolicy(DbMcpProperties properties) {
        return new OwnerAccessPolicy(properties.allowedOwners());
    }

    @Bean
    QueryLimitAppender queryLimitAppender(
            DbMcpProperties properties,
            ReadQueryDialect readQueryDialect) {
        return new QueryLimitAppender(
                properties.defaultLimit(),
                properties.maxLimit(),
                readQueryDialect);
    }

    @Bean
    SqlGuard sqlGuard() {
        return new SqlGuard();
    }

    @Bean
    MaskingService maskingService() {
        return new MaskingService();
    }
}
