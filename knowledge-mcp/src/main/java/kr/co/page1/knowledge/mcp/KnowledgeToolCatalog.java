package kr.co.page1.knowledge.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kr.co.page1.knowledge.config.KnowledgeProperties;
import kr.co.page1.knowledge.domain.KnowledgePattern;
import kr.co.page1.knowledge.domain.PatternCategory;
import kr.co.page1.knowledge.domain.PatternEvidence;
import kr.co.page1.knowledge.domain.PatternScope;
import kr.co.page1.knowledge.domain.PatternStatus;
import kr.co.page1.knowledge.service.ContextBundleService;
import kr.co.page1.knowledge.service.MarkdownImportService;
import kr.co.page1.knowledge.service.ObserveCommand;
import kr.co.page1.knowledge.service.PatternService;
import kr.co.page1.knowledge.service.RuleMarkdownService;
import org.springframework.stereotype.Component;

/**
 * The tool surface. Twelve tools, split four ways:
 *
 * <ul>
 *   <li>write  - observe / observe_batch / confirm / archive / import_markdown</li>
 *   <li>read   - context / search / inspect / stats</li>
 *   <li>review - conflicts / review (report only, never auto-resolve)</li>
 *   <li>emit   - sync_rules (project the database out as markdown files)</li>
 * </ul>
 *
 * Tool descriptions are load-bearing: they are the only instruction the calling
 * model gets about WHEN to reach for each one, so they name the trigger rather than
 * just restating the name.
 */
@Component
public class KnowledgeToolCatalog {

    private static final String CATEGORY_ENUM =
            "\"PREFERENCE\",\"CONVENTION\",\"WORKFLOW\",\"ARCHITECTURE\",\"DOMAIN\",\"PITFALL\",\"TOOLING\",\"REQUIREMENT\"";

    /** Ceiling on one observe_batch call. See the refusal message for the reasoning. */
    private static final int MAX_BATCH = 20;

    private final PatternService patternService;
    private final ContextBundleService bundleService;
    private final RuleMarkdownService markdownService;
    private final MarkdownImportService importService;
    private final KnowledgeProperties properties;
    private final Map<String, McpTool> tools = new LinkedHashMap<>();

    public KnowledgeToolCatalog(PatternService patternService,
                               ContextBundleService bundleService,
                               RuleMarkdownService markdownService,
                               MarkdownImportService importService,
                               KnowledgeProperties properties) {
        this.patternService = patternService;
        this.bundleService = bundleService;
        this.markdownService = markdownService;
        this.importService = importService;
        this.properties = properties;
        register();
    }

    public List<McpTool> all() {
        return List.copyOf(tools.values());
    }

    public Optional<McpTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    private void add(McpTool tool) {
        tools.put(tool.name(), tool);
    }

    private void register() {
        add(new McpTool("knowledge_observe", "Observe a pattern", """
                Record ONE durable thing you just learned about how this user wants work done: \
                a preference, a correction they made, a convention you had to be told, a pitfall you hit. \
                Call it the moment the user says "always", "never", "다음부터는", "그게 아니라", or corrects your output. \
                Repeat sightings of the same rule are merged, not duplicated, and a rule becomes ACTIVE \
                (and therefore injected into future sessions) once it has been seen enough times. \
                Do NOT record one-off task facts ("이 티켓은 5907001684") - only things that would change \
                your behaviour in a future, unrelated session.""",
                """
                {
                  "type": "object",
                  "properties": {
                    "statement": {"type": "string", "description": "The rule in imperative form, e.g. 'Gradle 검증은 짧게 끝낼 것'"},
                    "category": {"type": "string", "enum": [%s], "description": "Defaults to PREFERENCE"},
                    "antiPattern": {"type": "string", "description": "What NOT to do - the half of a correction that carries the signal"},
                    "rationale": {"type": "string", "description": "Why, so a later agent can tell when the rule stops applying"},
                    "scope": {"type": "string", "enum": ["GLOBAL", "PROJECT"], "description": "PROJECT rules are only injected for that project"},
                    "projectKey": {"type": "string", "description": "Required when scope is PROJECT"},
                    "tags": {"type": "array", "items": {"type": "string"}},
                    "sourceRef": {"type": "string", "description": "Where it came from - file path, PR, session id"},
                    "author": {"type": "string"},
                    "note": {"type": "string", "description": "Evidence for THIS sighting - the quote or context"},
                    "confirm": {"type": "boolean", "description": "True only when the user explicitly blessed it; promotes straight to ACTIVE"}
                  },
                  "required": ["statement"]
                }
                """.formatted(CATEGORY_ENUM),
                this::observe));

        add(new McpTool("knowledge_observe_batch", "Observe several patterns", """
                Record several patterns in one call. Use at the end of a turn that produced more than one \
                lesson, instead of calling knowledge_observe repeatedly.""",
                """
                {
                  "type": "object",
                  "properties": {
                    "patterns": {
                      "type": "array",
                      "description": "Each item takes the same fields as knowledge_observe",
                      "items": {"type": "object"}
                    }
                  },
                  "required": ["patterns"]
                }
                """,
                this::observeBatch));

        add(new McpTool("knowledge_context", "Get the injectable rule bundle", """
                Return the accumulated rules as ONE markdown document, ranked by confidence, frequency and \
                recency, and cut to a character budget. Call this at the START of a task to load what is \
                already known about this user and project - before asking them to repeat themselves. \
                Pass a query to pull rules about a specific topic that the default budget left out.""",
                """
                {
                  "type": "object",
                  "properties": {
                    "projectKey": {"type": "string", "description": "Include this project's rules alongside the global ones"},
                    "query": {"type": "string", "description": "Only rules relevant to this topic"},
                    "categories": {"type": "array", "items": {"type": "string", "enum": [%s]}},
                    "maxChars": {"type": "integer", "description": "Character budget; defaults to the server setting"},
                    "includeCandidates": {"type": "boolean", "description": "Also include not-yet-promoted CANDIDATE rules"}
                  }
                }
                """.formatted(CATEGORY_ENUM),
                this::context));

        add(new McpTool("knowledge_search", "Search stored patterns", """
                Keyword search across every stored pattern, including OBSERVED ones that are not yet rules \
                and ARCHIVED ones. Use when you want to check whether something was already recorded \
                before adding a near-duplicate, or to answer "what do we know about X".""",
                """
                {
                  "type": "object",
                  "properties": {
                    "query": {"type": "string"},
                    "category": {"type": "string", "enum": [%s]},
                    "status": {"type": "string", "enum": ["OBSERVED","CANDIDATE","ACTIVE","ARCHIVED"]},
                    "scope": {"type": "string", "enum": ["GLOBAL","PROJECT"]},
                    "projectKey": {"type": "string"},
                    "limit": {"type": "integer", "description": "Default 20"}
                  }
                }
                """.formatted(CATEGORY_ENUM),
                this::search));

        add(new McpTool("knowledge_confirm", "Confirm a pattern as a rule", """
                Promote a pattern straight to ACTIVE with full confidence. Call this when the USER \
                explicitly approves a rule, not on your own judgement.""",
                """
                {
                  "type": "object",
                  "properties": {
                    "id": {"type": "integer", "description": "Pattern id, as shown in knowledge_search"},
                    "author": {"type": "string"}
                  },
                  "required": ["id"]
                }
                """,
                this::confirm));

        add(new McpTool("knowledge_archive", "Archive an obsolete rule", """
                Retire a rule that no longer applies. It stops being injected but is kept for audit. \
                Pass supersededBy when a newer rule replaces it. Never archive a rule just because it is \
                inconvenient for the current task - only when it is actually wrong or obsolete.""",
                """
                {
                  "type": "object",
                  "properties": {
                    "id": {"type": "integer"},
                    "reason": {"type": "string"},
                    "supersededBy": {"type": "integer", "description": "Id of the rule that replaces this one"}
                  },
                  "required": ["id", "reason"]
                }
                """,
                this::archive));

        add(new McpTool("knowledge_sync_rules", "Write the markdown rule files", """
                Project the database out to markdown on disk: one file per category plus AGENT_CONTEXT.md, \
                and mirror the context into any configured export targets (e.g. a repo's steering file). \
                Runs automatically after an observation promotes a rule - call it manually to force a \
                rewrite or after editing rules through the REST API. Hand-written content outside the \
                generated block is preserved.""",
                """
                {
                  "type": "object",
                  "properties": {
                    "projectKey": {"type": "string"}
                  }
                }
                """,
                this::syncRules));

        add(new McpTool("knowledge_import_markdown", "Import rules from markdown", """
                Seed the store from an existing rules document - AGENTS.md, a steering file, a lessons list. \
                Parses bullet lists (including the "rule -- NOT: anti-pattern" convention) and this \
                server's own generated format. Give either a path or inline content. \
                Prose paragraphs are ignored on purpose; only list items become patterns.""",
                """
                {
                  "type": "object",
                  "properties": {
                    "path": {"type": "string", "description": "Absolute path to a markdown file on this machine"},
                    "content": {"type": "string", "description": "Markdown text, when you already have it"},
                    "category": {"type": "string", "enum": [%s], "description": "Category for bullets under a heading that names none"},
                    "scope": {"type": "string", "enum": ["GLOBAL","PROJECT"]},
                    "projectKey": {"type": "string"},
                    "confirm": {"type": "boolean", "description": "Treat imported rules as already blessed (ACTIVE). Default true."}
                  }
                }
                """.formatted(CATEGORY_ENUM),
                this::importMarkdown));

        add(new McpTool("knowledge_conflicts", "List rules that may contradict", """
                Report pairs of live rules that overlap enough to be suspicious but not enough to be \
                merged automatically - typically one saying to do X and another saying not to. \
                Resolution is a human call: show the pairs and ask, do not pick one yourself.""",
                """
                {
                  "type": "object",
                  "properties": {
                    "projectKey": {"type": "string"}
                  }
                }
                """,
                this::conflicts));

        add(new McpTool("knowledge_stats", "Store statistics", """
                Counts by status and category, plus where the store and generated files live and what the \
                promotion thresholds currently are. Use to answer "how much do you know about me".""",
                "{\"type\": \"object\", \"properties\": {}}",
                this::stats));

        add(new McpTool("knowledge_inspect", "Inspect one rule and its evidence", """
                Show one pattern in full with its sighting log - who recorded it, when, from where, and \
                whether each sighting was an exact match or folded in as a near-duplicate. \
                Use before confirming or archiving a rule, and whenever the user asks why a rule exists. \
                A rule you cannot justify from its evidence should not be defended.""",
                """
                {
                  "type": "object",
                  "properties": {
                    "id": {"type": "integer", "description": "Pattern id, as shown by knowledge_search"},
                    "evidenceLimit": {"type": "integer", "description": "Most recent sightings to show. Default 10"}
                  },
                  "required": ["id"]
                }
                """,
                this::inspect));

        add(new McpTool("knowledge_review", "List rules that need a decision", """
                Housekeeping: injected rules nobody has re-observed in a long time, candidates that stalled \
                short of promotion, and single sightings that went cold. Nothing is changed - this is the \
                list to walk with the user when the store starts feeling stale. \
                Do not archive from this list on your own judgement; a rule can be old simply because \
                nobody has had cause to break it.""",
                """
                {
                  "type": "object",
                  "properties": {
                    "projectKey": {"type": "string"},
                    "staleDays": {"type": "integer", "description": "Days without a sighting that counts as stale. Default 90"},
                    "limit": {"type": "integer", "description": "Max rows per bucket. Default 15"}
                  }
                }
                """,
                this::review));
    }

    // -------------------------------------------------------------------- write

    private ToolResult observe(JsonNode args) {
        ObserveCommand command = toCommand(args);
        PatternService.Outcome outcome = patternService.observe(command);
        if (outcome.becameInjectable()) {
            markdownService.syncIfEnabled();
        }
        return ToolResult.ok(describe(outcome), structured(outcome));
    }

    private ToolResult observeBatch(JsonNode args) {
        JsonNode array = Args.array(args, "patterns");
        if (array == null || array.isEmpty()) {
            return ToolResult.failed("patterns must be a non-empty array");
        }
        if (array.size() > MAX_BATCH) {
            // A turn that produced 30 "rules" did not learn 30 rules. Refusing is more
            // useful than silently ingesting a wall of noise that then gets injected.
            return ToolResult.failed(("%d patterns in one call, limit is %d. "
                    + "한 턴에서 그만큼 배우는 일은 없다 - 진짜 규칙만 골라 다시 보낼 것.")
                    .formatted(array.size(), MAX_BATCH));
        }
        List<ObserveCommand> commands = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        array.forEach(node -> {
            try {
                commands.add(toCommand(node));
            } catch (RuntimeException e) {
                rejected.add(e.getMessage());
            }
        });
        if (commands.isEmpty()) {
            return ToolResult.failed("no valid pattern in batch: " + String.join("; ", rejected));
        }

        List<PatternService.Outcome> outcomes = patternService.observeAll(commands);
        boolean anyPromoted = outcomes.stream().anyMatch(PatternService.Outcome::becameInjectable);
        if (anyPromoted) {
            markdownService.syncIfEnabled();
        }

        StringBuilder text = new StringBuilder("recorded ").append(outcomes.size()).append(" pattern(s)\n\n");
        outcomes.forEach(outcome -> text.append("- ").append(describe(outcome)).append('\n'));
        if (!rejected.isEmpty()) {
            text.append("\nrejected: ").append(String.join("; ", rejected)).append('\n');
        }
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("recorded", outcomes.stream().map(this::structured).toList());
        structured.put("rejected", rejected);
        return ToolResult.ok(text.toString(), structured);
    }

    private ToolResult confirm(JsonNode args) {
        Long id = Args.longOrNull(args, "id");
        if (id == null) {
            return ToolResult.failed("id is required");
        }
        KnowledgePattern pattern = patternService.confirm(id, Args.text(args, "author"));
        markdownService.syncIfEnabled();
        return ToolResult.ok("confirmed " + pattern.ref() + " -> ACTIVE (confidence 1.00): "
                + pattern.getStatement(), Map.of(
                "id", pattern.getId(),
                "ref", pattern.ref(),
                "status", pattern.getStatus().name()));
    }

    private ToolResult archive(JsonNode args) {
        Long id = Args.longOrNull(args, "id");
        if (id == null) {
            return ToolResult.failed("id is required");
        }
        String reason = Args.text(args, "reason");
        if (reason == null) {
            return ToolResult.failed("reason is required - an archived rule with no reason is unreviewable");
        }
        KnowledgePattern pattern = patternService.archive(id, reason, Args.longOrNull(args, "supersededBy"));
        markdownService.syncIfEnabled();
        return ToolResult.ok("archived " + pattern.ref() + ": " + reason, Map.of(
                "id", pattern.getId(),
                "ref", pattern.ref(),
                "status", pattern.getStatus().name()));
    }

    private ToolResult importMarkdown(JsonNode args) {
        String path = Args.text(args, "path");
        String content = Args.text(args, "content");
        if (path == null && content == null) {
            return ToolResult.failed("give either path or content");
        }
        if (content == null) {
            content = readFile(Path.of(path));
        }
        PatternScope scope = PatternScope.parse(Args.text(args, "scope", "GLOBAL"));
        String projectKey = Args.text(args, "projectKey", properties.defaultProjectKey());
        if (projectKey != null && projectKey.isBlank()) {
            projectKey = null;
        }
        List<String> skipped = new ArrayList<>();
        List<ObserveCommand> commands = importService.parse(
                content,
                PatternCategory.parse(Args.text(args, "category", "PREFERENCE")),
                scope,
                projectKey,
                path == null ? "inline-markdown" : path,
                Args.bool(args, "confirm", true),
                skipped);
        if (commands.isEmpty()) {
            return ToolResult.failed("no list item looked like a rule. skipped: " + skipped.size());
        }

        List<PatternService.Outcome> outcomes = patternService.observeAll(commands);
        markdownService.syncIfEnabled();

        long created = outcomes.stream().filter(o -> o.action() == PatternService.Action.CREATED).count();
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("parsed", commands.size());
        structured.put("created", created);
        structured.put("merged", outcomes.size() - created);
        structured.put("skipped", skipped.size());
        return ToolResult.ok("imported %d rule(s): %d new, %d merged into existing, %d line(s) skipped"
                .formatted(commands.size(), created, outcomes.size() - created, skipped.size()), structured);
    }

    // --------------------------------------------------------------------- read

    private ToolResult context(JsonNode args) {
        Set<PatternCategory> categories = new LinkedHashSet<>();
        Args.stringList(args, "categories").forEach(raw -> categories.add(PatternCategory.parse(raw)));
        ContextBundleService.Bundle bundle = bundleService.build(new ContextBundleService.BundleRequest(
                Args.text(args, "projectKey"),
                categories,
                Args.text(args, "query"),
                Args.integer(args, "maxChars", 0),
                Args.bool(args, "includeCandidates", false)));
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("included", bundle.included());
        structured.put("available", bundle.available());
        structured.put("truncated", bundle.truncated());
        structured.put("projectKey", bundle.projectKey());
        return ToolResult.ok(bundle.markdown(), structured);
    }

    private ToolResult search(JsonNode args) {
        List<PatternService.Hit> hits = patternService.search(
                Args.text(args, "query"),
                args.hasNonNull("category") ? PatternCategory.parse(Args.text(args, "category")) : null,
                PatternStatus.parse(Args.text(args, "status")),
                args.hasNonNull("scope") ? PatternScope.parse(Args.text(args, "scope")) : null,
                Args.text(args, "projectKey"),
                Args.integer(args, "limit", 20));
        if (hits.isEmpty()) {
            return ToolResult.ok("no match.", Map.of("hits", List.of()));
        }
        StringBuilder text = new StringBuilder("| id | ref | status | hits | statement |\n|---|---|---|---|---|\n");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PatternService.Hit hit : hits) {
            KnowledgePattern p = hit.pattern();
            text.append("| ").append(p.getId())
                    .append(" | ").append(p.ref())
                    .append(" | ").append(p.getStatus())
                    .append(" | ").append(p.getHitCount())
                    .append(" | ").append(oneLine(p.getStatement()))
                    .append(" |\n");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.getId());
            row.put("ref", p.ref());
            row.put("category", p.getCategory().name());
            row.put("status", p.getStatus().name());
            row.put("hitCount", p.getHitCount());
            row.put("confidence", round(p.getConfidence()));
            row.put("statement", p.getStatement());
            row.put("antiPattern", p.getAntiPattern());
            row.put("score", round(hit.score()));
            rows.add(row);
        }
        return ToolResult.ok(text.toString(), Map.of("hits", rows));
    }

    private ToolResult conflicts(JsonNode args) {
        List<PatternService.Conflict> conflicts = patternService.conflicts(Args.text(args, "projectKey"));
        if (conflicts.isEmpty()) {
            return ToolResult.ok("모순 후보 없음.", Map.of("conflicts", List.of()));
        }
        StringBuilder text = new StringBuilder("검토 필요 ").append(conflicts.size()).append("건 (자동 해결 안 함)\n\n");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PatternService.Conflict conflict : conflicts) {
            text.append("- ").append(conflict.left().ref()).append(" vs ").append(conflict.right().ref())
                    .append(" (overlap ").append(round(conflict.similarity())).append(")\n")
                    .append("  - A: ").append(oneLine(conflict.left().getStatement())).append('\n')
                    .append("  - B: ").append(oneLine(conflict.right().getStatement())).append('\n')
                    .append("  - ").append(conflict.reason()).append('\n');
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("leftId", conflict.left().getId());
            row.put("rightId", conflict.right().getId());
            row.put("similarity", round(conflict.similarity()));
            row.put("reason", conflict.reason());
            rows.add(row);
        }
        return ToolResult.ok(text.toString(), Map.of("conflicts", rows));
    }

    private ToolResult stats(JsonNode args) {
        Map<String, Object> stats = patternService.stats();
        StringBuilder text = new StringBuilder("## knowledge store\n\n");
        text.append("- home: `").append(stats.get("home")).append("`\n");
        text.append("- byStatus: ").append(stats.get("byStatus")).append('\n');
        text.append("- activeByCategory: ").append(stats.get("activeByCategory")).append('\n');
        text.append("- promotion: ").append(stats.get("promotion")).append('\n');
        return ToolResult.ok(text.toString(), stats);
    }

    private ToolResult inspect(JsonNode args) {
        Long id = Args.longOrNull(args, "id");
        if (id == null) {
            return ToolResult.failed("id is required");
        }
        KnowledgePattern p = patternService.require(id);
        List<PatternEvidence> evidence = patternService.evidence(id, Args.integer(args, "evidenceLimit", 10));

        StringBuilder text = new StringBuilder();
        text.append("## ").append(p.ref()).append(" · ").append(p.getStatus()).append('\n');
        text.append("- **Rule**: ").append(oneLine(p.getStatement())).append('\n');
        if (p.getAntiPattern() != null) {
            text.append("- **NOT**: ").append(oneLine(p.getAntiPattern())).append('\n');
        }
        if (p.getRationale() != null) {
            text.append("- **Why**: ").append(oneLine(p.getRationale())).append('\n');
        }
        text.append("- category ").append(p.getCategory())
                .append(" · scope ").append(p.scopeKey())
                .append(" · hits ").append(p.getHitCount())
                .append(" · confidence ").append(round(p.getConfidence())).append('\n');
        text.append("- first seen ").append(p.getFirstSeenAt())
                .append(" · last seen ").append(p.getLastSeenAt()).append('\n');
        if (p.getArchivedReason() != null) {
            text.append("- archived: ").append(p.getArchivedReason()).append('\n');
        }
        if (p.getSupersededBy() != null) {
            text.append("- superseded by id ").append(p.getSupersededBy()).append('\n');
        }

        text.append("\n### 근거 ").append(evidence.size()).append("건 (최근순)\n");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PatternEvidence e : evidence) {
            text.append("- ").append(e.recordedAt())
                    .append(e.similarity() < 1.0 ? " (near-dup " + round(e.similarity()) + ")" : "")
                    .append(" · ").append(e.author() == null ? "unknown" : e.author())
                    .append(" · ").append(oneLine(e.note()));
            if (e.sourceRef() != null) {
                text.append(" · ").append(e.sourceRef());
            }
            text.append('\n');

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("recordedAt", e.recordedAt().toString());
            row.put("author", e.author());
            row.put("sourceRef", e.sourceRef());
            row.put("similarity", round(e.similarity()));
            row.put("note", e.note());
            rows.add(row);
        }

        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("id", p.getId());
        structured.put("ref", p.ref());
        structured.put("category", p.getCategory().name());
        structured.put("status", p.getStatus().name());
        structured.put("scope", p.scopeKey());
        structured.put("hitCount", p.getHitCount());
        structured.put("confidence", round(p.getConfidence()));
        structured.put("statement", p.getStatement());
        structured.put("antiPattern", p.getAntiPattern());
        structured.put("rationale", p.getRationale());
        structured.put("evidence", rows);
        return ToolResult.ok(text.toString(), structured);
    }

    private ToolResult review(JsonNode args) {
        int limit = Math.max(1, Args.integer(args, "limit", 15));
        PatternService.Review review = patternService.review(
                Args.text(args, "projectKey"), Args.integer(args, "staleDays", 90));

        if (review.total() == 0) {
            return ToolResult.ok("정리할 것 없음 (%d일 기준).".formatted(review.staleDays()),
                    Map.of("total", 0, "staleDays", review.staleDays()));
        }

        StringBuilder text = new StringBuilder("## 정리 후보 %d건 (%d일 이상 미관찰)\n"
                .formatted(review.total(), review.staleDays()));
        text.append("_자동으로 손대지 않았다. 규칙이 오래된 건 아무도 어길 일이 없었다는 뜻일 수도 있다._\n");
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("staleDays", review.staleDays());
        structured.put("total", review.total());
        appendBucket(text, structured, "오래된 ACTIVE 규칙", "staleActive", review.staleActive(), limit);
        appendBucket(text, structured, "승격 못 한 CANDIDATE", "stuckCandidates", review.stuckCandidates(), limit);
        appendBucket(text, structured, "한 번 보고 식은 관찰", "coldObservations", review.coldObservations(), limit);
        return ToolResult.ok(text.toString(), structured);
    }

    private void appendBucket(StringBuilder text,
                              Map<String, Object> structured,
                              String heading,
                              String key,
                              List<KnowledgePattern> rows,
                              int limit) {
        List<Map<String, Object>> collected = new ArrayList<>();
        if (rows.isEmpty()) {
            structured.put(key, collected);
            return;
        }
        text.append("\n### ").append(heading).append(" (").append(rows.size()).append(")\n");
        int shown = 0;
        for (KnowledgePattern p : rows) {
            if (shown++ < limit) {
                text.append("- id ").append(p.getId()).append(" · ").append(p.ref())
                        .append(" · hits ").append(p.getHitCount())
                        .append(" · last ").append(p.getLastSeenAt())
                        .append("\n  ").append(oneLine(p.getStatement())).append('\n');
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.getId());
            row.put("ref", p.ref());
            row.put("hitCount", p.getHitCount());
            row.put("lastSeenAt", p.getLastSeenAt().toString());
            row.put("statement", p.getStatement());
            collected.add(row);
        }
        if (rows.size() > limit) {
            text.append("- ...").append(rows.size() - limit).append("건 더 (structuredContent 에 전체)\n");
        }
        structured.put(key, collected);
    }

    private ToolResult syncRules(JsonNode args) {
        RuleMarkdownService.SyncReport report = markdownService.sync(Args.text(args, "projectKey"));
        StringBuilder text = new StringBuilder();
        text.append("wrote ").append(report.ruleFiles().size()).append(" rule file(s), ")
                .append(report.activeRules()).append(" rule(s) total\n");
        text.append("- context: `").append(report.contextFile()).append("`\n");
        report.exportedTo().forEach(target -> text.append("- exported: `").append(target).append("`\n"));
        text.append("- perCategory: ").append(report.perCategory()).append('\n');
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("ruleFiles", report.ruleFiles());
        structured.put("contextFile", report.contextFile());
        structured.put("exportedTo", report.exportedTo());
        structured.put("rules", report.activeRules());
        structured.put("perCategory", report.perCategory());
        return ToolResult.ok(text.toString(), structured);
    }

    // ------------------------------------------------------------------ helpers

    private ObserveCommand toCommand(JsonNode args) {
        return new ObserveCommand(
                PatternCategory.parse(Args.text(args, "category", "PREFERENCE")),
                PatternScope.parse(Args.text(args, "scope", "GLOBAL")),
                Args.text(args, "projectKey"),
                Args.required(args, "statement"),
                Args.text(args, "antiPattern"),
                Args.text(args, "rationale"),
                Args.tags(args, "tags"),
                Args.text(args, "sourceRef"),
                Args.text(args, "author"),
                Args.text(args, "note"),
                Args.bool(args, "confirm", false));
    }

    private String describe(PatternService.Outcome outcome) {
        KnowledgePattern p = outcome.pattern();
        StringBuilder sb = new StringBuilder();
        sb.append(outcome.action()).append(' ').append(p.ref())
                .append(" · ").append(p.getStatus())
                .append(" · hits ").append(p.getHitCount());
        if (outcome.action() == PatternService.Action.MERGED && outcome.similarity() < 1.0) {
            sb.append(" · folded into an existing rule (overlap ")
                    .append(round(outcome.similarity())).append(')');
        }
        if (p.getStatus() != PatternStatus.ACTIVE) {
            int needed = properties.promotion().activeHits() - p.getHitCount();
            if (needed > 0) {
                sb.append(" · ").append(needed).append(" more sighting(s) to become an injected rule");
            }
        } else if (outcome.becameInjectable()) {
            sb.append(" · now injected into future sessions");
        }
        sb.append("\n> ").append(oneLine(p.getStatement()));
        return sb.toString();
    }

    private Map<String, Object> structured(PatternService.Outcome outcome) {
        KnowledgePattern p = outcome.pattern();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", outcome.action().name());
        out.put("id", p.getId());
        out.put("ref", p.ref());
        out.put("category", p.getCategory().name());
        out.put("status", p.getStatus().name());
        out.put("hitCount", p.getHitCount());
        out.put("confidence", round(p.getConfidence()));
        out.put("similarity", round(outcome.similarity()));
        out.put("statusChanged", outcome.statusChanged());
        out.put("statement", p.getStatement());
        return out;
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static double round(double value) {
        return Double.parseDouble(String.format(Locale.ROOT, "%.2f", value));
    }
}
