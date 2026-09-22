package kr.co.page1.knowledge.web;

import java.time.Instant;
import java.util.List;
import kr.co.page1.knowledge.domain.KnowledgePattern;

/**
 * REST shape of a pattern. Explicit rather than serialising the entity directly, so
 * the API does not change shape every time a field is added internally.
 */
public record PatternView(
        Long id,
        String ref,
        String category,
        String scope,
        String projectKey,
        String statement,
        String antiPattern,
        String rationale,
        List<String> tags,
        String status,
        int hitCount,
        double confidence,
        Instant firstSeenAt,
        Instant lastSeenAt,
        String author,
        String sourceRef,
        Long supersededBy,
        String archivedReason) {

    public static PatternView of(KnowledgePattern p) {
        return new PatternView(
                p.getId(),
                p.ref(),
                p.getCategory().name(),
                p.getScope().name(),
                p.getProjectKey(),
                p.getStatement(),
                p.getAntiPattern(),
                p.getRationale(),
                List.copyOf(p.tagSet()),
                p.getStatus().name(),
                p.getHitCount(),
                p.getConfidence(),
                p.getFirstSeenAt(),
                p.getLastSeenAt(),
                p.getAuthor(),
                p.getSourceRef(),
                p.getSupersededBy(),
                p.getArchivedReason());
    }
}
