package kr.co.page1.knowledge.domain;

import java.util.Locale;

/**
 * GLOBAL rules follow the user everywhere; PROJECT rules are only injected when
 * the caller asks for that project. Keeping the two apart is what stops one
 * repo's quirks from leaking into every other session.
 */
public enum PatternScope {

    GLOBAL,
    PROJECT;

    public static PatternScope parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return GLOBAL;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "global", "user", "all" -> GLOBAL;
            case "project", "repo", "local" -> PROJECT;
            default -> throw new IllegalArgumentException("unknown scope: " + raw + " (allowed: GLOBAL, PROJECT)");
        };
    }
}
