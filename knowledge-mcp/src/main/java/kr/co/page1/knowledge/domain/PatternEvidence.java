package kr.co.page1.knowledge.domain;

import java.time.Instant;

/**
 * One sighting of a pattern. This is the audit trail that answers "why does this
 * rule exist?" months later - without it a promoted rule is just an assertion.
 *
 * @param similarity 1.0 for an exact fingerprint match, lower when the sighting
 *                   was folded in by near-duplicate detection
 */
public record PatternEvidence(
        Long id,
        Long patternId,
        String note,
        String author,
        String sourceRef,
        double similarity,
        Instant recordedAt) {

    public static PatternEvidence of(String note, String author, String sourceRef, double similarity) {
        return new PatternEvidence(null, null, note, author, sourceRef, similarity, Instant.now());
    }
}
