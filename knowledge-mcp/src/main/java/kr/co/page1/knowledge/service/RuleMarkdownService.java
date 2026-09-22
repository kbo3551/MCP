package kr.co.page1.knowledge.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import kr.co.page1.knowledge.config.KnowledgeProperties;
import kr.co.page1.knowledge.domain.KnowledgePattern;
import kr.co.page1.knowledge.domain.PatternCategory;
import kr.co.page1.knowledge.domain.PatternScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Writes the rules out as markdown on disk.
 *
 * <p>Generated content lives between {@link #BEGIN_MARKER} and {@link #END_MARKER}.
 * Anything outside those markers is left untouched, so a human can keep notes in
 * the same file without them being wiped on the next sync - which is the only way
 * a generated-file workflow survives contact with a real repo.
 */
@Service
public class RuleMarkdownService {

    public static final String BEGIN_MARKER = "<!-- knowledge-mcp:begin -- generated, do not edit inside this block -->";
    public static final String END_MARKER = "<!-- knowledge-mcp:end -->";

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private static final Logger log = LoggerFactory.getLogger(RuleMarkdownService.class);

    public record SyncReport(
            List<String> ruleFiles,
            String contextFile,
            List<String> exportedTo,
            int activeRules,
            Map<String, Integer> perCategory) {
    }

    private final PatternService patternService;
    private final ContextBundleService bundleService;
    private final KnowledgeProperties properties;

    public RuleMarkdownService(PatternService patternService,
                               ContextBundleService bundleService,
                               KnowledgeProperties properties) {
        this.patternService = patternService;
        this.bundleService = bundleService;
        this.properties = properties;
    }

    /**
     * Regenerate every rules file plus AGENT_CONTEXT.md, and mirror the context into
     * the configured export targets.
     *
     * @param projectKey which project's rules to include alongside the global ones
     */
    public SyncReport sync(String projectKey) {
        Instant now = Instant.now();
        List<KnowledgePattern> rules = patternService.injectable(projectKey, properties.bundle().includeCandidates());

        Map<PatternCategory, List<KnowledgePattern>> grouped = new EnumMap<>(PatternCategory.class);
        for (KnowledgePattern p : rules) {
            grouped.computeIfAbsent(p.getCategory(), k -> new ArrayList<>()).add(p);
        }
        grouped.values().forEach(list -> list.sort(
                Comparator.comparingDouble((KnowledgePattern p) ->
                        PatternScorer.score(p, now, properties.bundle().halfLifeDays())).reversed()));

        Path rulesDir = properties.rulesDir();
        createDirectories(rulesDir);

        List<String> written = new ArrayList<>();
        Map<String, Integer> perCategory = new LinkedHashMap<>();
        for (PatternCategory category : PatternCategory.values()) {
            List<KnowledgePattern> list = grouped.getOrDefault(category, List.of());
            perCategory.put(category.name(), list.size());
            // Files with no rules are still written, so the directory shows the full
            // taxonomy and a human can see which buckets are empty.
            Path file = rulesDir.resolve(category.fileName());
            writeManagedBlock(file, "# " + category.heading(), renderCategory(category, list, now));
            written.add(file.toString());
        }

        String context = bundleService.markdownFor(projectKey, properties.bundle().maxChars());
        Path contextFile = properties.contextFile();
        createDirectories(contextFile.getParent());
        writeString(contextFile, context);

        List<String> exported = new ArrayList<>();
        for (String target : properties.sync().targets()) {
            if (target == null || target.isBlank()) {
                continue;
            }
            try {
                Path path = Path.of(target.trim());
                createDirectories(path.getParent());
                // Managed block here too: a steering file may hold hand-written rules
                // that must survive the next sync.
                writeManagedBlock(path, "# Project Knowledge (knowledge-mcp)", context);
                exported.add(path.toString());
            } catch (RuntimeException e) {
                log.warn("export target failed: {} ({})", target, e.getMessage());
            }
        }

        log.info("rules synced: {} files, {} rules, context={}", written.size(), rules.size(), contextFile);
        return new SyncReport(written, contextFile.toString(), exported, rules.size(), perCategory);
    }

    /**
     * Called after an observation changed the injectable set, when sync.on-write is on.
     *
     * <p>Always projects {@code knowledge.default-project-key}, deliberately ignoring
     * the project key of whatever observation fired it. The files on disk are a
     * projection of ONE project context; keying them off the triggering observation
     * would make them flip between including and excluding project rules depending on
     * which observation happened to come last. Pass an explicit key to {@link
     * #sync(String)} to project a different project on purpose.
     */
    public void syncIfEnabled() {
        if (!properties.sync().onWrite()) {
            return;
        }
        try {
            sync(properties.defaultProjectKey());
        } catch (RuntimeException e) {
            // A failed markdown write must never fail the observation that triggered it:
            // the database is the source of truth, the files are a projection.
            log.warn("auto-sync failed, database is still consistent: {}", e.getMessage());
        }
    }

    /** Raw content of one generated rules file, for the MCP resources surface. */
    public String readRuleFile(PatternCategory category) {
        Path file = properties.rulesDir().resolve(category.fileName());
        if (!Files.exists(file)) {
            return "# " + category.heading() + "\n\n_아직 생성되지 않았다. `knowledge_sync_rules` 를 호출할 것._\n";
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    private String renderCategory(PatternCategory category, List<KnowledgePattern> rules, Instant now) {
        StringBuilder sb = new StringBuilder();
        sb.append("_").append(category.hint()).append("_\n\n");
        sb.append("_generated ").append(STAMP.format(now)).append(" · ").append(rules.size()).append(" rules_\n\n");
        if (rules.isEmpty()) {
            sb.append("_(없음)_\n");
            return sb.toString();
        }
        for (KnowledgePattern p : rules) {
            sb.append("### ").append(p.ref()).append(" · ").append(oneLine(p.getStatement())).append("\n\n");
            sb.append("- **Rule**: ").append(oneLine(p.getStatement())).append('\n');
            if (p.getAntiPattern() != null) {
                sb.append("- **NOT**: ").append(oneLine(p.getAntiPattern())).append('\n');
            }
            if (p.getRationale() != null) {
                sb.append("- **Why**: ").append(oneLine(p.getRationale())).append('\n');
            }
            sb.append("- **Evidence**: hits ").append(p.getHitCount())
                    .append(" · confidence ").append(String.format(Locale.ROOT, "%.2f", p.getConfidence()))
                    .append(" · ").append(p.getStatus())
                    .append(" · first ").append(STAMP.format(p.getFirstSeenAt()))
                    .append(" · last ").append(STAMP.format(p.getLastSeenAt()))
                    .append('\n');
            sb.append("- **Scope**: ")
                    .append(p.getScope() == PatternScope.PROJECT ? "project:" + p.getProjectKey() : "global");
            if (!p.tagSet().isEmpty()) {
                sb.append(" · tags: ").append(String.join(", ", p.tagSet()));
            }
            if (p.getSourceRef() != null) {
                sb.append(" · source: ").append(oneLine(p.getSourceRef()));
            }
            sb.append("\n\n");
        }
        return sb.toString();
    }

    /**
     * Replace only the managed block of {@code file}, creating the file (with
     * {@code heading}) when it does not exist and appending the block when the file
     * exists but has no markers yet.
     */
    void writeManagedBlock(Path file, String heading, String generated) {
        String block = BEGIN_MARKER + "\n\n" + generated.stripTrailing() + "\n\n" + END_MARKER;
        String next;
        if (!Files.exists(file)) {
            next = heading + "\n\n" + block + "\n";
        } else {
            String existing = readString(file);
            int begin = existing.indexOf(BEGIN_MARKER);
            int end = existing.indexOf(END_MARKER);
            if (begin >= 0 && end > begin) {
                next = existing.substring(0, begin) + block + existing.substring(end + END_MARKER.length());
            } else {
                next = existing.stripTrailing() + "\n\n" + block + "\n";
            }
        }
        writeString(file, next);
    }

    private static String readString(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    private static void writeString(Path file, String content) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }

    private static void createDirectories(Path dir) {
        if (dir == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create " + dir, e);
        }
    }

    /** Category set helper used by the tool layer. */
    public static Set<PatternCategory> allCategories() {
        return Set.of(PatternCategory.values());
    }
}
