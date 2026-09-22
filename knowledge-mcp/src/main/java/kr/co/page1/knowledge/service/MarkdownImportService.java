package kr.co.page1.knowledge.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.co.page1.knowledge.domain.PatternCategory;
import kr.co.page1.knowledge.domain.PatternScope;
import org.springframework.stereotype.Service;

/**
 * Reads rules back OUT of markdown, so an existing AGENTS.md / steering file can
 * seed the store instead of starting from zero.
 *
 * <p>Handles two shapes: this server's own generated format (round-trip), and the
 * flat bullet style that hand-written rule files use, including the
 * {@code "... -- NOT: ..."} convention for pairing a rule with its anti-pattern.
 */
@Service
public class MarkdownImportService {

    /** "rule text -- NOT: what not to do", with the dash written any of several ways. */
    private static final Pattern NOT_SPLIT =
            Pattern.compile("\\s+(?:--|—|–|-)\\s*(?:NOT|하지 말 것|금지)\\s*[:：]\\s*", Pattern.CASE_INSENSITIVE);

    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-*+]|\\d+\\.)\\s+(.*)$");
    private static final Pattern HEADING = Pattern.compile("^\\s*#{1,6}\\s+(.*)$");
    private static final Pattern LABELLED =
            Pattern.compile("^\\s*[-*+]\\s+\\*\\*(Rule|NOT|Why|Evidence|Scope)\\*\\*\\s*[:：]\\s*(.*)$",
                    Pattern.CASE_INSENSITIVE);
    /** Our own "### PREF-12 · statement" line - the following Rule bullet carries it. */
    private static final Pattern REF_HEADING = Pattern.compile("^\\s*#{2,6}\\s+[A-Z]{3,4}-\\d+\\s*·.*$");

    private static final int MIN_STATEMENT_LENGTH = 8;

    public record ImportReport(int parsed, List<String> skipped) {
    }

    /**
     * @param defaultCategory used for bullets that appear before/without a heading
     *                        that maps onto a category
     */
    public List<ObserveCommand> parse(String markdown,
                                      PatternCategory defaultCategory,
                                      PatternScope scope,
                                      String projectKey,
                                      String sourceRef,
                                      boolean confirm,
                                      List<String> skipped) {
        List<ObserveCommand> out = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return out;
        }

        PatternCategory category = defaultCategory == null ? PatternCategory.PREFERENCE : defaultCategory;
        Draft draft = new Draft();
        boolean inCodeFence = false;

        for (String raw : markdown.split("\r?\n")) {
            String line = raw.strip();

            if (line.startsWith("```")) {
                inCodeFence = !inCodeFence;
                continue;
            }
            if (inCodeFence || line.isEmpty()
                    || line.startsWith(RuleMarkdownService.BEGIN_MARKER.substring(0, 20))) {
                continue;
            }

            Matcher labelled = LABELLED.matcher(line);
            if (labelled.matches()) {
                String label = labelled.group(1).toLowerCase(Locale.ROOT);
                String value = labelled.group(2).replace("**", "").strip();
                switch (label) {
                    case "rule" -> {
                        flush(draft, out, category, scope, projectKey, sourceRef, confirm, skipped);
                        draft.statement = value;
                    }
                    case "not" -> draft.antiPattern = value;
                    case "why" -> draft.rationale = value;
                    default -> {
                        // Evidence / Scope are regenerated, never imported.
                    }
                }
                continue;
            }

            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                flush(draft, out, category, scope, projectKey, sourceRef, confirm, skipped);
                if (REF_HEADING.matcher(line).matches()) {
                    continue;
                }
                PatternCategory resolved = resolveCategory(heading.group(1));
                if (resolved != null) {
                    category = resolved;
                }
                continue;
            }

            Matcher bullet = BULLET.matcher(line);
            if (bullet.matches()) {
                flush(draft, out, category, scope, projectKey, sourceRef, confirm, skipped);
                String text = bullet.group(1).replace("**", "").strip();
                if (text.length() < MIN_STATEMENT_LENGTH) {
                    skipped.add("too short: " + text);
                    continue;
                }
                String[] halves = NOT_SPLIT.split(text, 2);
                draft.statement = halves[0].strip();
                if (halves.length > 1) {
                    draft.antiPattern = halves[1].strip();
                }
                flush(draft, out, category, scope, projectKey, sourceRef, confirm, skipped);
            }
            // Free prose is intentionally ignored: importing paragraphs as rules is
            // how a knowledge base fills with noise.
        }
        flush(draft, out, category, scope, projectKey, sourceRef, confirm, skipped);
        return out;
    }

    private void flush(Draft draft,
                       List<ObserveCommand> out,
                       PatternCategory category,
                       PatternScope scope,
                       String projectKey,
                       String sourceRef,
                       boolean confirm,
                       List<String> skipped) {
        if (draft.statement == null || draft.statement.isBlank()) {
            draft.reset();
            return;
        }
        String statement = draft.statement.strip();
        if (statement.length() < MIN_STATEMENT_LENGTH) {
            skipped.add("too short: " + statement);
            draft.reset();
            return;
        }
        try {
            out.add(new ObserveCommand(category, scope, projectKey, statement,
                    draft.antiPattern, draft.rationale, null, sourceRef, "markdown-import",
                    "imported from " + (sourceRef == null ? "markdown" : sourceRef), confirm));
        } catch (IllegalArgumentException e) {
            // One over-long or malformed bullet must not abort the whole import - the
            // point of importing an existing document is to get the rest of it in.
            skipped.add(e.getMessage());
        }
        draft.reset();
    }

    private PatternCategory resolveCategory(String headingText) {
        String text = headingText.replace("#", "").replace("*", "").strip().toLowerCase(Locale.ROOT);
        for (PatternCategory candidate : PatternCategory.values()) {
            if (text.contains(candidate.heading().toLowerCase(Locale.ROOT))
                    || text.contains(candidate.name().toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
        return null;
    }

    private static final class Draft {
        String statement;
        String antiPattern;
        String rationale;

        void reset() {
            statement = null;
            antiPattern = null;
            rationale = null;
        }
    }
}
