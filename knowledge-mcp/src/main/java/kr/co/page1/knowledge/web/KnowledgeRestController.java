package kr.co.page1.knowledge.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.co.page1.knowledge.domain.PatternCategory;
import kr.co.page1.knowledge.domain.PatternScope;
import kr.co.page1.knowledge.domain.PatternStatus;
import kr.co.page1.knowledge.service.ContextBundleService;
import kr.co.page1.knowledge.service.ObserveCommand;
import kr.co.page1.knowledge.service.PatternService;
import kr.co.page1.knowledge.service.RuleMarkdownService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plain HTTP over the same services the MCP tools use.
 *
 * <p>This is the "sharing" half: a second agent, a teammate's editor, a CI job or a
 * dashboard can read the rules and contribute observations without speaking MCP.
 * The MCP tool layer and this controller are two faces of one store, never two
 * stores that have to be kept in step.
 */
@RestController
@RequestMapping("/api")
public class KnowledgeRestController {

    /** Request body for POST /api/patterns - same fields as the observe tool. */
    public record ObserveRequest(
            @NotBlank String statement,
            String category,
            String scope,
            String projectKey,
            String antiPattern,
            String rationale,
            List<String> tags,
            String sourceRef,
            String author,
            String note,
            Boolean confirm) {
    }

    private final PatternService patternService;
    private final ContextBundleService bundleService;
    private final RuleMarkdownService markdownService;

    public KnowledgeRestController(PatternService patternService,
                                   ContextBundleService bundleService,
                                   RuleMarkdownService markdownService) {
        this.patternService = patternService;
        this.bundleService = bundleService;
        this.markdownService = markdownService;
    }

    /** The injection bundle as raw markdown, so `curl` output is directly usable. */
    @GetMapping(path = "/context", produces = "text/markdown;charset=UTF-8")
    public String context(@RequestParam(required = false) String projectKey,
                          @RequestParam(required = false) String query,
                          @RequestParam(required = false, defaultValue = "0") int maxChars,
                          @RequestParam(required = false, defaultValue = "false") boolean includeCandidates) {
        return bundleService.build(new ContextBundleService.BundleRequest(
                projectKey, Set.of(), query, maxChars, includeCandidates)).markdown();
    }

    @GetMapping("/patterns")
    public List<PatternView> patterns(@RequestParam(required = false) String q,
                                      @RequestParam(required = false) String category,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String scope,
                                      @RequestParam(required = false) String projectKey,
                                      @RequestParam(required = false, defaultValue = "50") int limit) {
        List<PatternService.Hit> hits = patternService.search(
                q,
                category == null ? null : PatternCategory.parse(category),
                PatternStatus.parse(status),
                scope == null ? null : PatternScope.parse(scope),
                projectKey,
                limit);
        List<PatternView> out = new ArrayList<>(hits.size());
        hits.forEach(hit -> out.add(PatternView.of(hit.pattern())));
        return out;
    }

    @PostMapping(path = "/patterns", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> observe(@Valid @RequestBody ObserveRequest request) {
        ObserveCommand command = new ObserveCommand(
                PatternCategory.parse(request.category()),
                PatternScope.parse(request.scope()),
                request.projectKey(),
                request.statement(),
                request.antiPattern(),
                request.rationale(),
                request.tags() == null || request.tags().isEmpty() ? null : String.join(",", request.tags()),
                request.sourceRef(),
                request.author(),
                request.note(),
                Boolean.TRUE.equals(request.confirm()));

        PatternService.Outcome outcome = patternService.observe(command);
        if (outcome.becameInjectable()) {
            markdownService.syncIfEnabled();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", outcome.action().name());
        body.put("similarity", outcome.similarity());
        body.put("statusChanged", outcome.statusChanged());
        body.put("pattern", PatternView.of(outcome.pattern()));
        return outcome.action() == PatternService.Action.CREATED
                ? ResponseEntity.status(201).body(body)
                : ResponseEntity.ok(body);
    }

    @PostMapping("/patterns/{id}/confirm")
    public PatternView confirm(@PathVariable long id, @RequestParam(required = false) String author) {
        PatternView view = PatternView.of(patternService.confirm(id, author));
        markdownService.syncIfEnabled();
        return view;
    }

    @PostMapping("/patterns/{id}/archive")
    public PatternView archive(@PathVariable long id,
                               @RequestParam String reason,
                               @RequestParam(required = false) Long supersededBy) {
        PatternView view = PatternView.of(patternService.archive(id, reason, supersededBy));
        markdownService.syncIfEnabled();
        return view;
    }

    @GetMapping("/patterns/{id}")
    public PatternView pattern(@PathVariable long id) {
        return PatternView.of(patternService.require(id));
    }

    @GetMapping("/patterns/{id}/evidence")
    public List<Map<String, Object>> evidence(@PathVariable long id,
                                              @RequestParam(required = false, defaultValue = "20") int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        patternService.evidence(id, limit).forEach(evidence -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", evidence.id());
            row.put("note", evidence.note());
            row.put("author", evidence.author());
            row.put("sourceRef", evidence.sourceRef());
            row.put("similarity", evidence.similarity());
            row.put("recordedAt", evidence.recordedAt());
            out.add(row);
        });
        return out;
    }

    @GetMapping("/conflicts")
    public List<Map<String, Object>> conflicts(@RequestParam(required = false) String projectKey) {
        List<Map<String, Object>> out = new ArrayList<>();
        patternService.conflicts(projectKey).forEach(conflict -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("similarity", conflict.similarity());
            row.put("reason", conflict.reason());
            row.put("left", PatternView.of(conflict.left()));
            row.put("right", PatternView.of(conflict.right()));
            out.add(row);
        });
        return out;
    }

    @PostMapping("/rules/sync")
    public RuleMarkdownService.SyncReport sync(@RequestParam(required = false) String projectKey) {
        return markdownService.sync(projectKey);
    }

    @GetMapping("/review")
    public Map<String, Object> review(@RequestParam(required = false) String projectKey,
                                     @RequestParam(required = false, defaultValue = "90") int staleDays) {
        PatternService.Review review = patternService.review(projectKey, staleDays);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("staleDays", review.staleDays());
        body.put("total", review.total());
        body.put("staleActive", review.staleActive().stream().map(PatternView::of).toList());
        body.put("stuckCandidates", review.stuckCandidates().stream().map(PatternView::of).toList());
        body.put("coldObservations", review.coldObservations().stream().map(PatternView::of).toList());
        return body;
    }

    /**
     * Liveness plus the one fact that matters operationally: is the store readable.
     * Answers 503 when it is not - a monitor that only reads the body would otherwise
     * treat a dead store as healthy.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            Map<String, Object> stats = patternService.stats();
            body.put("status", "UP");
            body.put("store", stats.get("byStatus"));
            body.put("home", stats.get("home"));
            return ResponseEntity.ok(body);
        } catch (RuntimeException e) {
            body.put("status", "DOWN");
            body.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            return ResponseEntity.status(503).body(body);
        }
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return patternService.stats();
    }
}
