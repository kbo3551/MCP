package kr.co.page1.knowledge.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.co.page1.knowledge.config.KnowledgeProperties;
import kr.co.page1.knowledge.domain.KnowledgePattern;
import kr.co.page1.knowledge.domain.PatternCategory;
import kr.co.page1.knowledge.domain.PatternScope;
import org.springframework.stereotype.Service;

/**
 * Turns the stored rules into the single markdown document an agent is given.
 *
 * <p>The budget is the whole point: a knowledge store that grows without bound and
 * is pasted in wholesale stops being useful the moment it outgrows the context
 * window. Rules are ranked, then cut at a character budget, and the document says
 * out loud when it was cut so the agent knows to pull more with a query.
 */
@Service
public class ContextBundleService {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** Empty {@code categories} means every category. */
    public record BundleRequest(
            String projectKey,
            Set<PatternCategory> categories,
            String query,
            int maxChars,
            boolean includeCandidates) {
    }

    public record Bundle(
            String markdown,
            int included,
            int available,
            boolean truncated,
            String projectKey) {
    }

    private final PatternService patternService;
    private final KnowledgeProperties properties;

    public ContextBundleService(PatternService patternService, KnowledgeProperties properties) {
        this.patternService = patternService;
        this.properties = properties;
    }

    public Bundle build(BundleRequest request) {
        int budget = request.maxChars() > 0 ? request.maxChars() : properties.bundle().maxChars();
        boolean includeCandidates = request.includeCandidates() || properties.bundle().includeCandidates();
        String projectKey = patternService.effectiveProjectKey(request.projectKey());
        Instant now = Instant.now();
        double halfLife = properties.bundle().halfLifeDays();

        List<KnowledgePattern> all = patternService.injectable(projectKey, includeCandidates);
        List<KnowledgePattern> filtered = new ArrayList<>();
        Set<String> queryTokens = TextNormalizer.tokens(request.query());
        for (KnowledgePattern p : all) {
            if (request.categories() != null && !request.categories().isEmpty()
                    && !request.categories().contains(p.getCategory())) {
                continue;
            }
            if (!queryTokens.isEmpty() && !matchesQuery(p, queryTokens, request.query())) {
                continue;
            }
            filtered.add(p);
        }
        filtered.sort(Comparator.comparingDouble((KnowledgePattern p) -> PatternScorer.score(p, now, halfLife))
                .reversed());

        StringBuilder head = new StringBuilder();
        head.append("# Agent Knowledge Context\n\n");
        head.append("_generated ").append(STAMP.format(now))
                .append(" · source `").append(properties.serverName()).append('`')
                .append(" · scope ").append(projectKey == null ? "global" : "global + project:" + projectKey);
        if (!queryTokens.isEmpty()) {
            head.append(" · query \"").append(request.query().trim()).append('"');
        }
        head.append("_\n\n");
        head.append("> 아래 규칙은 이 사용자와의 실제 작업에서 반복 관찰되어 승격된 항목이다. ")
                .append("추측이 아니라 근거(hits)가 있는 지시이므로 그대로 따를 것.\n")
                .append("> 새로운 규칙이나 교정을 발견하면 `knowledge_observe` 로 기록하고, ")
                .append("여기 없는 주제가 필요하면 `knowledge_context` 에 query 를 주어 더 가져올 것.\n\n");

        // Group in category order so the document reads the same way every time.
        Map<PatternCategory, List<KnowledgePattern>> grouped = new EnumMap<>(PatternCategory.class);
        int included = 0;
        int used = head.length();
        boolean truncated = false;
        for (KnowledgePattern p : filtered) {
            String rendered = renderRule(p, now, halfLife);
            int cost = rendered.length() + (grouped.containsKey(p.getCategory()) ? 0 : 40);
            if (used + cost > budget) {
                truncated = true;
                continue;
            }
            grouped.computeIfAbsent(p.getCategory(), k -> new ArrayList<>()).add(p);
            used += cost;
            included++;
        }

        StringBuilder body = new StringBuilder(head);
        for (PatternCategory category : PatternCategory.values()) {
            List<KnowledgePattern> rules = grouped.get(category);
            if (rules == null || rules.isEmpty()) {
                continue;
            }
            body.append("## ").append(category.heading()).append("\n\n");
            for (KnowledgePattern p : rules) {
                body.append(renderRule(p, now, halfLife));
            }
            body.append('\n');
        }
        if (included == 0) {
            body.append("_아직 승격된 규칙이 없다. `knowledge_observe` 로 패턴을 기록하면 ")
                    .append(properties.promotion().activeHits())
                    .append("회 관찰 시점에 자동으로 규칙이 된다._\n");
        }
        if (truncated) {
            body.append("\n---\n_예산(").append(budget).append("자)에 맞춰 ")
                    .append(filtered.size() - included)
                    .append("건을 생략했다. 특정 주제가 필요하면 `knowledge_context` 에 query 또는 categories 를 지정할 것._\n");
        }

        return new Bundle(body.toString(), included, filtered.size(), truncated, projectKey);
    }

    /** Convenience for the resource/prompt/initialize paths that just want the text. */
    public String markdownFor(String projectKey, int maxChars) {
        return build(new BundleRequest(projectKey, Set.of(), null, maxChars, false)).markdown();
    }

    private String renderRule(KnowledgePattern p, Instant now, double halfLife) {
        StringBuilder sb = new StringBuilder();
        sb.append("- **").append(oneLine(p.getStatement())).append("**\n");
        if (p.getAntiPattern() != null) {
            sb.append("  - NOT: ").append(oneLine(p.getAntiPattern())).append('\n');
        }
        if (p.getRationale() != null) {
            sb.append("  - WHY: ").append(oneLine(p.getRationale())).append('\n');
        }
        sb.append("  - `").append(p.ref()).append("` · hits ").append(p.getHitCount())
                .append(" · conf ").append(String.format(java.util.Locale.ROOT, "%.2f", p.getConfidence()))
                .append(" · ").append(p.getStatus())
                .append(" · ").append(p.getScope() == PatternScope.PROJECT ? "project:" + p.getProjectKey() : "global")
                .append(" · last ").append(STAMP.format(p.getLastSeenAt()));
        if (!p.tagSet().isEmpty()) {
            sb.append(" · tags ").append(String.join(", ", p.tagSet()));
        }
        sb.append('\n');
        return sb.toString();
    }

    private boolean matchesQuery(KnowledgePattern p, Set<String> queryTokens, String rawQuery) {
        String haystack = p.getStatement() + " " + nullToEmpty(p.getAntiPattern())
                + " " + nullToEmpty(p.getRationale()) + " " + nullToEmpty(p.getTags());
        if (TextNormalizer.similarity(queryTokens, TextNormalizer.tokens(haystack)) > 0.0) {
            return true;
        }
        return TextNormalizer.normalize(haystack).contains(TextNormalizer.normalize(rawQuery));
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
