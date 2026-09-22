package kr.co.page1.knowledge.domain;

import java.util.Locale;

/**
 * Lifecycle of one observation on its way to becoming a rule.
 *
 * <pre>
 *   OBSERVED  seen once - kept, but never injected (one-off requests are noise)
 *   CANDIDATE seen candidate-hits times - injected only when explicitly asked for
 *   ACTIVE    seen active-hits times, or confirmed by a human - always injected
 *   ARCHIVED  obsolete or superseded - kept for audit, never injected
 * </pre>
 */
public enum PatternStatus {

    OBSERVED,
    CANDIDATE,
    ACTIVE,
    ARCHIVED;

    public boolean injectable() {
        return this == ACTIVE || this == CANDIDATE;
    }

    public static PatternStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return PatternStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}
