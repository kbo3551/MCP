package kr.co.page1.knowledge.mcp;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Newline-delimited JSON on stdin/stdout - the transport a desktop MCP client uses
 * when it launches this server as a child process.
 *
 * <p>Reads and writes the raw file descriptors rather than {@code System.in}/
 * {@code System.out}, so the protocol channel stays intact even though
 * {@code System.out} has been rerouted to stderr to keep stray prints out of it.
 */
@Component
@Profile("stdio")
public class StdioTransport implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StdioTransport.class);

    private final McpServer server;
    private final ConfigurableApplicationContext context;
    private final Object writeLock = new Object();

    public StdioTransport(McpServer server, ConfigurableApplicationContext context) {
        this.server = server;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
        log.info("stdio transport ready");

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(FileDescriptor.in), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String response;
                try {
                    response = server.handleLine(line);
                } catch (RuntimeException e) {
                    // handleLine already converts protocol-level problems into error
                    // responses; reaching here means a bug, and dying would drop the
                    // session, so log and keep serving.
                    log.error("unhandled failure while processing a message", e);
                    continue;
                }
                if (response == null) {
                    continue;
                }
                synchronized (writeLock) {
                    out.print(response);
                    out.print('\n');
                    out.flush();
                }
            }
            log.info("stdin closed, shutting down");
        } catch (IOException e) {
            log.error("stdio transport failed", e);
        } finally {
            // The client closed the pipe: exit instead of lingering as an orphan.
            int exitCode = org.springframework.boot.SpringApplication.exit(context, () -> 0);
            System.exit(exitCode);
        }
    }
}
