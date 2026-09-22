package kr.co.page1.knowledge;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;

/**
 * Two ways to run, one codebase:
 *
 * <ul>
 *   <li>default - HTTP server: {@code POST /mcp} for MCP clients that speak
 *       streamable HTTP, plus the REST API that makes the store shareable</li>
 *   <li>{@code --stdio} - one MCP session on stdin/stdout, which is how a desktop
 *       client (Kiro, Claude Desktop) launches a local server</li>
 * </ul>
 *
 * In stdio mode stdout belongs to the protocol. Anything else printing there - a
 * banner, a stray log line, a stack trace - corrupts the stream and the client
 * simply reports the server as broken, so {@code System.out} is rerouted to stderr
 * before Spring starts and the transport writes to the real file descriptor.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class KnowledgeMcpApplication {

    public static void main(String[] args) {
        boolean stdio = stdioRequested(args);
        if (stdio) {
            System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        }

        SpringApplication application = new SpringApplication(KnowledgeMcpApplication.class);
        application.addListeners((ApplicationListener<ApplicationEvent>) KnowledgeMcpApplication::prepareHome);
        if (stdio) {
            application.setAdditionalProfiles("stdio");
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setBannerMode(Banner.Mode.OFF);
            application.setLogStartupInfo(false);
        }
        application.run(args);
    }

    /**
     * Create the store directories on ApplicationPreparedEvent - after the
     * Environment is fully resolved, before the DataSource is instantiated. H2
     * creates its own parent directory, but rules/ and logs/ are written by code that
     * should not have to check first.
     *
     * <p>Registered as a catch-all listener with an instanceof guard on purpose: a
     * lambda's generic event type is not recoverable at runtime, so a narrowly typed
     * lambda listener would be handed every event and rely on Spring swallowing the
     * resulting ClassCastException.
     */
    private static void prepareHome(ApplicationEvent event) {
        if (!(event instanceof ApplicationPreparedEvent prepared)) {
            return;
        }
        String home = prepared.getApplicationContext().getEnvironment()
                .getProperty("knowledge.home", System.getProperty("user.home") + "/.knowledge-mcp");
        for (String child : new String[]{"db", "rules", "logs"}) {
            try {
                Files.createDirectories(Path.of(home, child));
            } catch (IOException | RuntimeException e) {
                // Not fatal: the failure resurfaces with a better message from
                // whichever component actually needs the directory.
                System.err.println("[knowledge-mcp] cannot create " + home + "/" + child + ": " + e.getMessage());
            }
        }
    }

    private static boolean stdioRequested(String[] args) {
        boolean flagged = Arrays.stream(args).anyMatch(arg ->
                arg.equals("--stdio")
                        || arg.equals("stdio")
                        || arg.equalsIgnoreCase("--mcp.transport=stdio")
                        || arg.equalsIgnoreCase("--knowledge.transport=stdio"));
        if (flagged) {
            return true;
        }
        String env = System.getenv("KNOWLEDGE_MCP_TRANSPORT");
        return env != null && env.trim().equalsIgnoreCase("stdio");
    }
}
