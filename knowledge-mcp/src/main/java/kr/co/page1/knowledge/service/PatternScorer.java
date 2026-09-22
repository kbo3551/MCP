package kr.co.page1.knowledge.service;

import java.time.Duration;
import java.time.Instant;
import kr.co.page1.knowledge.domain.KnowledgePattern;
import kr.co.page1.knowledge.domain.PatternStatus;

/**
 * Ranking for the injection bundle. Three signals, multiplied:
 *
 * <ul>
 *   <li>confidence - how sure we are this is a real rule</li>
 *   <li>frequency  - log-damped hit count, so 20 sightings does not bury everything else</li>
 *   <li>recency    - exponential decay, so last week's correction outranks a rule from spring</li>
 * </ul>
 *
 * A CANDIDATE is discounted rather than excluded, so raising the budget surfaces
 * the weaker material at the bottom instead of reshuffling the top.
 */
public final class PatternScorer {

    private PatternScorer() {
    }

    public static double score(KnowledgePattern pattern, Instant now, double halfLifeDays) {
        double frequency = 1.0 + Math.log1p(Math.max(0, pattern.getHitCount()));
        double recency = recencyWeight(pattern.getLastSeenAt(), now, halfLifeDays);
        double statusWeight = pattern.getStatus() == PatternStatus.ACTIVE ? 1.0 : 0.6;
        return Math.max(0.01, pattern.getConfidence()) * frequency * recency * statusWeight;
    }

    /** 1.0 when just seen, 0.5 after one half-life, floored at 0.25 so old rules never vanish. */
    public static double recencyWeight(Instant lastSeenAt, Instant now, double halfLifeDays) {
        if (lastSeenAt == null || halfLifeDays <= 0) {
            return 1.0;
        }
        double days = Math.max(0, Duration.between(lastSeenAt, now).toMinutes() / 1440.0);
        double weight = Math.pow(0.5, days / halfLifeDays);
        return Math.max(0.25, Math.min(1.0, weight));
    }

    /** Confidence after n sightings. Caps below 1.0 - only a human confirm reaches certainty. */
    public static double confidenceFor(int hitCount) {
        return Math.min(0.95, 0.30 + 0.20 * Math.max(0, hitCount - 1));
    }
}
