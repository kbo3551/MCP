package kr.co.page1.knowledge.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One durable thing learned about how the user wants work done.
 *
 * <p>Mutable on purpose: {@code observe()} merges repeat sightings into an
 * existing row instead of appending a new one, so the row is edited in place and
 * the occurrence log lives in {@link PatternEvidence}.
 */
public class KnowledgePattern {

    private Long id;
    private String fingerprint;
    private PatternCategory category;
    private PatternScope scope;
    private String projectKey;

    /** The rule in imperative form: "do X". */
    private String statement;
    /** What NOT to do. The half of a correction that carries most of the signal. */
    private String antiPattern;
    /** Why the rule exists, so a future agent can tell when it stops applying. */
    private String rationale;

    private String tags;
    private PatternStatus status = PatternStatus.OBSERVED;
    private int hitCount;
    private double confidence;
    private Instant firstSeenAt;
    private Instant lastSeenAt;
    private String author;
    private String sourceRef;
    private Long supersededBy;
    private String archivedReason;

    private final List<PatternEvidence> evidence = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public PatternCategory getCategory() {
        return category;
    }

    public void setCategory(PatternCategory category) {
        this.category = category;
    }

    public PatternScope getScope() {
        return scope;
    }

    public void setScope(PatternScope scope) {
        this.scope = scope;
    }

    public String getProjectKey() {
        return projectKey;
    }

    public void setProjectKey(String projectKey) {
        this.projectKey = projectKey;
    }

    public String getStatement() {
        return statement;
    }

    public void setStatement(String statement) {
        this.statement = statement;
    }

    public String getAntiPattern() {
        return antiPattern;
    }

    public void setAntiPattern(String antiPattern) {
        this.antiPattern = antiPattern;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public PatternStatus getStatus() {
        return status;
    }

    public void setStatus(PatternStatus status) {
        this.status = status;
    }

    public int getHitCount() {
        return hitCount;
    }

    public void setHitCount(int hitCount) {
        this.hitCount = hitCount;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public void setFirstSeenAt(Instant firstSeenAt) {
        this.firstSeenAt = firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    public Long getSupersededBy() {
        return supersededBy;
    }

    public void setSupersededBy(Long supersededBy) {
        this.supersededBy = supersededBy;
    }

    public String getArchivedReason() {
        return archivedReason;
    }

    public void setArchivedReason(String archivedReason) {
        this.archivedReason = archivedReason;
    }

    public List<PatternEvidence> getEvidence() {
        return evidence;
    }

    /** Stable human-facing id used in generated markdown, e.g. {@code PREF-12}. */
    public String ref() {
        String prefix = switch (category) {
            case PREFERENCE -> "PREF";
            case CONVENTION -> "CONV";
            case WORKFLOW -> "FLOW";
            case ARCHITECTURE -> "ARCH";
            case DOMAIN -> "DOM";
            case PITFALL -> "PIT";
            case TOOLING -> "TOOL";
            case REQUIREMENT -> "REQ";
        };
        return prefix + "-" + (id == null ? "new" : id);
    }

    public String scopeKey() {
        return scope == PatternScope.PROJECT && projectKey != null && !projectKey.isBlank()
                ? "project:" + projectKey.trim()
                : "global";
    }

    public Set<String> tagSet() {
        if (tags == null || tags.isBlank()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .forEach(out::add);
        return out;
    }

    /** Merge incoming tags into the existing set without duplicating. */
    public void mergeTags(String incoming) {
        if (incoming == null || incoming.isBlank()) {
            return;
        }
        Set<String> merged = new LinkedHashSet<>(tagSet());
        Arrays.stream(incoming.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .forEach(merged::add);
        this.tags = String.join(",", merged);
    }
}
