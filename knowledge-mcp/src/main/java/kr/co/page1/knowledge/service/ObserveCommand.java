package kr.co.page1.knowledge.service;

import kr.co.page1.knowledge.domain.PatternCategory;
import kr.co.page1.knowledge.domain.PatternScope;

/**
 * One thing an agent noticed about how the user wants work done.
 *
 * @param statement   the rule in imperative form - "Gradle 검증은 짧게 끝낼 것"
 * @param antiPattern what not to do; the half of a correction that carries the signal
 * @param rationale   why, so a later agent can tell when the rule stops applying
 * @param note        free-form evidence for this specific sighting (quote, context)
 * @param confirm     true when a human explicitly blessed it - jumps straight to ACTIVE
 */
public record ObserveCommand(
        PatternCategory category,
        PatternScope scope,
        String projectKey,
        String statement,
        String antiPattern,
        String rationale,
        String tags,
        String sourceRef,
        String author,
        String note,
        boolean confirm) {

    /**
     * Length ceilings. These REJECT rather than truncate: the storage layer caps
     * columns anyway, and silently shortening a rule produces a half-sentence that
     * reads like a rule and means something else. A model that pasted a paragraph
     * needs to be told to summarise it, not to have the tail cut off.
     */
    public static final int MAX_STATEMENT = 300;
    public static final int MAX_ANTI_PATTERN = 800;
    public static final int MAX_RATIONALE = 1200;
    /** Shorter than this is a fragment, not a rule. */
    public static final int MIN_STATEMENT = 6;

    public ObserveCommand {
        if (statement == null || statement.isBlank()) {
            throw new IllegalArgumentException("statement is required");
        }
        if (category == null) {
            category = PatternCategory.PREFERENCE;
        }
        if (scope == null) {
            scope = PatternScope.GLOBAL;
        }

        // A rule is one line. Collapsing whitespace here means a multi-line paste is
        // measured and stored as what it actually is.
        statement = collapse(statement);
        antiPattern = collapse(antiPattern);
        rationale = collapse(rationale);

        if (statement.length() < MIN_STATEMENT) {
            throw new IllegalArgumentException(
                    "statement is too short to be a rule (min " + MIN_STATEMENT + " chars): " + statement);
        }
        check("statement", statement, MAX_STATEMENT,
                "규칙 한 줄로 요약해서 다시 보내고, 배경 설명은 rationale 로 나눌 것");
        check("antiPattern", antiPattern, MAX_ANTI_PATTERN, "하지 말 것만 한 줄로");
        check("rationale", rationale, MAX_RATIONALE, "핵심 근거만");

        if (scope == PatternScope.PROJECT && (projectKey == null || projectKey.isBlank())) {
            throw new IllegalArgumentException("projectKey is required when scope is PROJECT");
        }
        if (projectKey != null) {
            projectKey = projectKey.trim();
        }
    }

    private static void check(String field, String value, int max, String hint) {
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException(
                    "%s is %d chars, limit is %d - %s".formatted(field, value.length(), max, hint));
        }
    }

    private static String collapse(String value) {
        if (value == null) {
            return null;
        }
        String single = value.replaceAll("\\s+", " ").trim();
        return single.isEmpty() ? null : single;
    }
}
