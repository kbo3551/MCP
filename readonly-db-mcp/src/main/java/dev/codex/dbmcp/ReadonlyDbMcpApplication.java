package dev.codex.dbmcp;

import dev.codex.dbmcp.infrastructure.config.DbMcpProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(DbMcpProperties.class)
public class ReadonlyDbMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReadonlyDbMcpApplication.class, args);
    }
}
