package kr.co.page1.knowledge.config;

import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything tunable about the knowledge server. Bound from the {@code knowledge.*}
 * block of application.yml.
 */
@ConfigurationProperties(prefix = "knowledge")
public record KnowledgeProperties(

        /** Root for the H2 file, generated markdown and logs. */
        @DefaultValue("${user.home}/.knowledge-mcp") Path home,

        /** Name reported to MCP clients during initialize. */
        @DefaultValue("knowledge-mcp") String serverName,

        @DefaultValue("0.1.0") String serverVersion,

        /** Default project key used when a caller does not pass one. */
        @DefaultValue("") String defaultProjectKey,

        @DefaultValue Promotion promotion,
        @DefaultValue Bundle bundle,
        @DefaultValue Inject inject,
        @DefaultValue Sync sync,
        @DefaultValue Security security) {

    public Path rulesDir() {
        return home.resolve("rules");
    }

    public Path contextFile() {
        return home.resolve("AGENT_CONTEXT.md");
    }

    /**
     * How many sightings it takes before an observation is treated as a rule.
     *
     * @param mergeThreshold token-overlap above which a new observation is folded
     *                       into an existing pattern instead of creating a second row
     * @param conflictFloor  overlap above which two distinct ACTIVE rules are
     *                       reported as a possible contradiction for a human to judge
     */
    public record Promotion(
            @DefaultValue("2") int candidateHits,
            @DefaultValue("3") int activeHits,
            @DefaultValue("true") boolean autoActivate,
            @DefaultValue("0.82") double mergeThreshold,
            @DefaultValue("0.55") double conflictFloor) {
    }

    /**
     * Shape of the markdown bundle handed back to an agent.
     *
     * @param halfLifeDays a rule not seen for this long counts half as much when
     *                     ordering the bundle - recent corrections win over old ones
     */
    public record Bundle(
            @DefaultValue("6000") int maxChars,
            @DefaultValue("45") double halfLifeDays,
            @DefaultValue("false") boolean includeCandidates) {
    }

    /**
     * Automatic injection. {@code onInitialize} is the part that makes this feel
     * automatic: the rules are shipped inside the MCP initialize response, so a
     * client that surfaces server instructions gets them with no tool call.
     */
    public record Inject(
            @DefaultValue("true") boolean onInitialize,
            @DefaultValue("4000") int maxChars) {
    }

    /**
     * @param onWrite      regenerate the markdown whenever an observation changes
     *                     the injectable set
     * @param exportTargets absolute paths that AGENT_CONTEXT.md is mirrored into,
     *                      e.g. {@code <repo>/.kiro/steering/knowledge.md} - this is
     *                      the file-based injection path for agents that read
     *                      steering files on their own
     */
    public record Sync(
            @DefaultValue("true") boolean onWrite,
            List<String> exportTargets) {

        /** Null-safe view; binding leaves the list null when the key is absent. */
        public List<String> targets() {
            return exportTargets == null ? List.of() : exportTargets;
        }
    }

    /**
     * @param token when set, every HTTP request must carry it in
     *              {@code X-Knowledge-Token}. Empty means the HTTP API is
     *              UNAUTHENTICATED - safe only because the server binds to
     *              127.0.0.1 by default.
     */
    public record Security(@DefaultValue("") String token) {

        public boolean enabled() {
            return token != null && !token.isBlank();
        }
    }
}
