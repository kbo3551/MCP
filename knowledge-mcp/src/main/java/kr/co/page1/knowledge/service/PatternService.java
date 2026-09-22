package kr.co.page1.knowledge.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
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
import kr.co.page1.knowledge.repository.PatternRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The learning loop. Everything an agent observes lands here, gets folded into an
 * existing pattern when it is the same thing said differently, and is promoted
 * from noise to rule only once it has been seen enough times.
 */
@Service
public class PatternService {

    private static final Logger log = LoggerFactory.getLogger(PatternService.class);

    public enum Action {
        /** A new pattern row was created. */
        CREATED,
        /** Folded into an existing pattern (exact fingerprint or near-duplicate). */
        MERGED
    }

    /**
     * @param similarity 1.0 for an exact fingerprint hit, otherwise the token overlap
     *                   that justified the merge
     */
    public record Outcome(
            Action action,
            KnowledgePattern pattern,
            double similarity,
            PatternStatus previousStatus,
            boolean statusChanged) {

        public boolean becameInjectable() {
            return statusChanged && pattern.getStatus().injectable();
        }
    }

    /** Two live rules that overlap enough to need a human to pick one. */
    public record Conflict(KnowledgePattern left, KnowledgePattern right, double similarity, String reason) {
    }

    /**
     * What needs a human decision.
     *
     * @param staleActive      injected rules nobody has re-observed in a while - maybe
     *                         obsolete, maybe just never violated
     * @param stuckCandidates  seen twice and then never again - promote or drop
     * @param coldObservations seen once, long ago - almost always noise
     */
    public record Review(
            int staleDays,
            List<KnowledgePattern> staleActive,
            List<KnowledgePattern> stuckCandidates,
            List<KnowledgePattern> coldObservations) {

        public int total() {
            return staleActive.size() + stuckCandidates.size() + coldObservations.size();
        }
    }

    public record Hit(KnowledgePattern pattern, double score, double relevance) {
    }

    private final PatternRepository repository;
    private final KnowledgeProperties properties;

    public PatternService(PatternRepository repository, KnowledgeProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ observe

    @Transactional
    public Outcome observe(ObserveCommand cmd) {
        Instant now = Instant.now();
        String projectKey = resolveProjectKey(cmd.scope(), cmd.projectKey());
        String scopeKey = cmd.scope() == PatternScope.PROJECT ? "project:" + projectKey : "global";
        String fingerprint = TextNormalizer.fingerprint(cmd.category().name(), scopeKey, cmd.statement());

        Optional<KnowledgePattern> exact = repository.findByFingerprint(fingerprint);
        if (exact.isPresent()) {
            return merge(exact.get(), cmd, 1.0, now);
        }

        Optional<KnowledgePattern> near = findNearDuplicate(cmd, projectKey);
        if (near.isPresent()) {
            double similarity = TextNormalizer.similarity(near.get().getStatement(), cmd.statement());
            return merge(near.get(), cmd, similarity, now);
        }

        KnowledgePattern created = new KnowledgePattern();
        created.setFingerprint(fingerprint);
        created.setCategory(cmd.category());
        created.setScope(cmd.scope());
        created.setProjectKey(cmd.scope() == PatternScope.PROJECT ? projectKey : null);
        created.setStatement(cmd.statement());
        created.setAntiPattern(blankToNull(cmd.antiPattern()));
        created.setRationale(blankToNull(cmd.rationale()));
        created.mergeTags(cmd.tags());
        created.setHitCount(1);
        created.setAuthor(blankToNull(cmd.author()));
        created.setSourceRef(blankToNull(cmd.sourceRef()));
        created.setFirstSeenAt(now);
        created.setLastSeenAt(now);
        applyPromotion(created, cmd.confirm());

        long id;
        try {
            id = repository.insert(created);
        } catch (DuplicateKeyException e) {
            // Another process (the HTTP server, a second stdio session - they share one
            // H2 file via AUTO_SERVER) inserted this exact fingerprint between our
            // lookup and our insert. The correct outcome is the merge we would have
            // done had we seen it, not a failed observation.
            KnowledgePattern winner = repository.findByFingerprint(fingerprint).orElseThrow(
                    () -> new IllegalStateException("fingerprint " + fingerprint
                            + " collided on insert but is not readable", e));
            log.info("insert raced on fingerprint {}, merging into id={}", fingerprint, winner.getId());
            return merge(winner, cmd, 1.0, now);
        }

        repository.insertEvidence(id, new PatternEvidence(
                null, id, evidenceNote(cmd), cmd.author(), cmd.sourceRef(), 1.0, now));
        log.info("pattern created id={} category={} status={} statement={}",
                id, created.getCategory(), created.getStatus(), abbreviate(created.getStatement()));
        return new Outcome(Action.CREATED, created, 1.0, null, true);
    }

    @Transactional
    public List<Outcome> observeAll(List<ObserveCommand> commands) {
        List<Outcome> outcomes = new ArrayList<>(commands.size());
        for (ObserveCommand cmd : commands) {
            outcomes.add(observe(cmd));
        }
        return outcomes;
    }

    private Outcome merge(KnowledgePattern existing, ObserveCommand cmd, double similarity, Instant now) {
        PatternStatus before = existing.getStatus();
        existing.setHitCount(existing.getHitCount() + 1);
        existing.setLastSeenAt(now);

        // Never overwrite an existing field with a blank one: a terse repeat sighting
        // must not erase the rationale a richer earlier sighting supplied.
        if (existing.getAntiPattern() == null && blankToNull(cmd.antiPattern()) != null) {
            existing.setAntiPattern(cmd.antiPattern().trim());
        }
        if (existing.getRationale() == null && blankToNull(cmd.rationale()) != null) {
            existing.setRationale(cmd.rationale().trim());
        }
        if (existing.getSourceRef() == null && blankToNull(cmd.sourceRef()) != null) {
            existing.setSourceRef(cmd.sourceRef().trim());
        }
        existing.mergeTags(cmd.tags());
        if (existing.getStatus() == PatternStatus.ARCHIVED) {
            // Re-observed after being archived: it is alive again, back to CANDIDATE.
            existing.setStatus(PatternStatus.CANDIDATE);
            existing.setArchivedReason(null);
            existing.setSupersededBy(null);
        }
        applyPromotion(existing, cmd.confirm());

        repository.update(existing);
        repository.insertEvidence(existing.getId(), new PatternEvidence(
                null, existing.getId(), evidenceNote(cmd), cmd.author(), cmd.sourceRef(), similarity, now));

        boolean changed = before != existing.getStatus();
        log.info("pattern merged id={} hits={} similarity={} {}->{}",
                existing.getId(), existing.getHitCount(), String.format(Locale.ROOT, "%.2f", similarity),
                before, existing.getStatus());
        return new Outcome(Action.MERGED, existing, similarity, before, changed);
    }

    /**
     * Same category, same scope, statement close enough that treating it as a second
     * rule would just produce two near-identical bullets in the generated markdown.
     */
    private Optional<KnowledgePattern> findNearDuplicate(ObserveCommand cmd, String projectKey) {
        double threshold = properties.promotion().mergeThreshold();
        Set<String> incoming = TextNormalizer.tokens(cmd.statement());
        return repository.findMergeCandidates(cmd.category(), cmd.scope(), projectKey).stream()
                .map(candidate -> Map.entry(candidate,
                        TextNormalizer.similarity(incoming, TextNormalizer.tokens(candidate.getStatement()))))
                .filter(entry -> entry.getValue() >= threshold)
                .max(Comparator.comparingDouble(Map.Entry::getValue))
                .map(Map.Entry::getKey);
    }

    private void applyPromotion(KnowledgePattern pattern, boolean confirmed) {
        KnowledgeProperties.Promotion rules = properties.promotion();
        if (confirmed) {
            pattern.setStatus(PatternStatus.ACTIVE);
            pattern.setConfidence(1.0);
            return;
        }
        pattern.setConfidence(PatternScorer.confidenceFor(pattern.getHitCount()));
        if (pattern.getStatus() == PatternStatus.ACTIVE) {
            return; // already a rule; more sightings only raise the hit count
        }
        if (rules.autoActivate() && pattern.getHitCount() >= rules.activeHits()) {
            pattern.setStatus(PatternStatus.ACTIVE);
        } else if (pattern.getHitCount() >= rules.candidateHits()) {
            pattern.setStatus(PatternStatus.CANDIDATE);
        } else {
            pattern.setStatus(PatternStatus.OBSERVED);
        }
    }

    // ----------------------------------------------------------------- lifecycle

    @Transactional
    public KnowledgePattern confirm(long id, String author) {
        KnowledgePattern pattern = require(id);
        pattern.setStatus(PatternStatus.ACTIVE);
        pattern.setConfidence(1.0);
        pattern.setLastSeenAt(Instant.now());
        pattern.setArchivedReason(null);
        repository.update(pattern);
        repository.insertEvidence(id, PatternEvidence.of("confirmed by " + orUnknown(author), author, null, 1.0));
        return pattern;
    }

    @Transactional
    public KnowledgePattern archive(long id, String reason, Long supersededBy) {
        KnowledgePattern pattern = require(id);
        pattern.setStatus(PatternStatus.ARCHIVED);
        pattern.setArchivedReason(blankToNull(reason));
        pattern.setSupersededBy(supersededBy);
        repository.update(pattern);
        repository.insertEvidence(id, PatternEvidence.of("archived: " + orUnknown(reason), null, null, 1.0));
        return pattern;
    }

    // -------------------------------------------------------------------- reads

    public List<KnowledgePattern> injectable(String projectKey, boolean includeCandidates) {
        Set<PatternStatus> statuses = includeCandidates
                ? EnumSet.of(PatternStatus.ACTIVE, PatternStatus.CANDIDATE)
                : EnumSet.of(PatternStatus.ACTIVE);
        // A missing project key is not an error here: it just means "global rules only".
        return repository.findInjectable(statuses, effectiveProjectKey(projectKey));
    }

    /** projectKey as given, else the configured default, else null. Never throws. */
    public String effectiveProjectKey(String projectKey) {
        if (projectKey != null && !projectKey.isBlank()) {
            return projectKey.trim();
        }
        String fallback = properties.defaultProjectKey();
        return fallback == null || fallback.isBlank() ? null : fallback.trim();
    }

    /**
     * Keyword search. Relevance is token overlap against statement + rationale +
     * tags; a blank query degrades to "everything, newest first".
     */
    public List<Hit> search(String query,
                            PatternCategory category,
                            PatternStatus status,
                            PatternScope scope,
                            String projectKey,
                            int limit) {
        List<KnowledgePattern> rows = repository.search(category, status, scope, projectKey, Math.max(limit, 200));
        Instant now = Instant.now();
        double halfLife = properties.bundle().halfLifeDays();
        Set<String> queryTokens = TextNormalizer.tokens(query);

        List<Hit> hits = new ArrayList<>();
        for (KnowledgePattern p : rows) {
            double relevance = 1.0;
            if (!queryTokens.isEmpty()) {
                String haystack = p.getStatement() + " " + orEmpty(p.getAntiPattern())
                        + " " + orEmpty(p.getRationale()) + " " + orEmpty(p.getTags());
                relevance = TextNormalizer.similarity(queryTokens, TextNormalizer.tokens(haystack));
                if (relevance <= 0.0 && !TextNormalizer.normalize(haystack)
                        .contains(TextNormalizer.normalize(query))) {
                    continue;
                }
                relevance = Math.max(relevance, 0.05);
            }
            hits.add(new Hit(p, PatternScorer.score(p, now, halfLife), relevance));
        }
        hits.sort(Comparator.comparingDouble((Hit h) -> h.relevance() * h.score()).reversed());
        return hits.size() > limit ? hits.subList(0, limit) : hits;
    }

    /**
     * Pairs of live rules that overlap enough to be suspicious but not enough to be
     * auto-merged. Reported, never resolved automatically - picking between two
     * plausible rules is a human call.
     */
    public List<Conflict> conflicts(String projectKey) {
        List<KnowledgePattern> rows = injectable(projectKey, true);
        double floor = properties.promotion().conflictFloor();
        double ceiling = properties.promotion().mergeThreshold();
        List<Conflict> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            for (int j = i + 1; j < rows.size(); j++) {
                KnowledgePattern left = rows.get(i);
                KnowledgePattern right = rows.get(j);
                if (left.getCategory() != right.getCategory()) {
                    continue;
                }
                double similarity = TextNormalizer.similarity(left.getStatement(), right.getStatement());
                if (similarity < floor || similarity >= ceiling) {
                    continue;
                }
                boolean polarityDiffers = TextNormalizer.isNegated(left.getStatement())
                        != TextNormalizer.isNegated(right.getStatement());
                out.add(new Conflict(left, right, similarity, polarityDiffers
                        ? "겹치는 주제인데 한쪽은 금지문, 한쪽은 지시문 - 서로 반대일 가능성"
                        : "같은 주제를 다르게 서술 - 하나로 합치거나 한쪽을 archive"));
            }
        }
        out.sort(Comparator.comparingDouble(Conflict::similarity).reversed());
        return out;
    }

    /**
     * Housekeeping view. Nothing here is acted on automatically - an ACTIVE rule that
     * has not been seen for months may be obsolete or may simply be a rule nobody has
     * had cause to violate, and only the user can tell those apart.
     *
     * @param staleDays how long without a sighting counts as stale
     */
    public Review review(String projectKey, int staleDays) {
        int days = Math.max(1, staleDays);
        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        String scoped = effectiveProjectKey(projectKey);

        List<KnowledgePattern> staleActive = new ArrayList<>();
        List<KnowledgePattern> stuckCandidates = new ArrayList<>();
        List<KnowledgePattern> coldObservations = new ArrayList<>();

        for (KnowledgePattern p : repository.search(null, null, null, null, 1000)) {
            if (scoped != null && p.getProjectKey() != null && !scoped.equals(p.getProjectKey())) {
                continue;
            }
            if (p.getLastSeenAt().isAfter(cutoff)) {
                continue;
            }
            switch (p.getStatus()) {
                case ACTIVE -> staleActive.add(p);
                case CANDIDATE -> stuckCandidates.add(p);
                case OBSERVED -> coldObservations.add(p);
                case ARCHIVED -> {
                    // already retired, nothing to review
                }
            }
        }
        return new Review(days, staleActive, stuckCandidates, coldObservations);
    }

    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("byStatus", repository.countByStatus());
        out.put("activeByCategory", repository.countActiveByCategory());
        out.put("home", properties.home().toString());
        out.put("rulesDir", properties.rulesDir().toString());
        out.put("promotion", Map.of(
                "candidateHits", properties.promotion().candidateHits(),
                "activeHits", properties.promotion().activeHits(),
                "autoActivate", properties.promotion().autoActivate(),
                "mergeThreshold", properties.promotion().mergeThreshold()));
        return out;
    }

    public List<PatternEvidence> evidence(long id, int limit) {
        return repository.findEvidence(id, limit);
    }

    public KnowledgePattern require(long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("no pattern with id " + id));
    }

    // ------------------------------------------------------------------ helpers

    private String resolveProjectKey(PatternScope scope, String projectKey) {
        if (projectKey != null && !projectKey.isBlank()) {
            return projectKey.trim();
        }
        if (scope == PatternScope.PROJECT) {
            String fallback = properties.defaultProjectKey();
            if (fallback == null || fallback.isBlank()) {
                throw new IllegalArgumentException(
                        "projectKey is required for PROJECT scope and knowledge.default-project-key is not set");
            }
            return fallback.trim();
        }
        return properties.defaultProjectKey() == null || properties.defaultProjectKey().isBlank()
                ? null
                : properties.defaultProjectKey().trim();
    }

    private static String evidenceNote(ObserveCommand cmd) {
        if (cmd.note() != null && !cmd.note().isBlank()) {
            return cmd.note().trim();
        }
        return "observed: " + abbreviate(cmd.statement());
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String single = value.replaceAll("\\s+", " ").trim();
        return single.length() <= 120 ? single : single.substring(0, 117) + "...";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? "unspecified" : value.trim();
    }
}
