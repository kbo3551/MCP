package dev.codex.dbmcp.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class DbMcpPropertiesBindingTest {

    @Test
    void bindsCommaSeparatedAllowedOwners() {
        MapConfigurationPropertySource source =
                new MapConfigurationPropertySource(Map.of(
                        "db-mcp.allowed-owners", "APP,REPORTING,COMMON",
                        "db-mcp.default-limit", "100",
                        "db-mcp.max-limit", "500",
                        "db-mcp.query-timeout-seconds", "30"));

        DbMcpProperties properties =
                new Binder(source).bind("db-mcp", DbMcpProperties.class).get();

        assertThat(properties.allowedOwners())
                .containsExactly("APP", "REPORTING", "COMMON");
    }
}
