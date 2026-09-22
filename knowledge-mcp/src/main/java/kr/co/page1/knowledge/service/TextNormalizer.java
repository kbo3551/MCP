package kr.co.page1.knowledge.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Text plumbing for dedupe. Deliberately dumb and dependency-free: no stemming,
 * no embeddings. The same rule phrased the same way must collapse to one row, and
 * the same rule phrased slightly differently must be *offered* as a merge - that
 * is all this needs to do.
 */
public final class TextNormalizer {

    /** Anything that is not a letter, digit or Hangul syllable is a separator. */
    private static final Pattern SEPARATOR = Pattern.compile("[^\\p{IsHangul}\\p{IsAlphabetic}\\p{IsDigit}]+");

    /**
     * Korean/English filler that carries no rule signal. Dropping it keeps
     * "gradle 빌드는 짧게 할 것" and "gradle 빌드 짧게" on the same fingerprint.
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "것", "때", "수", "좀", "그냥", "하고", "해서", "하지", "말것", "말고", "하세요", "해줘", "해주세요",
            "the", "a", "an", "to", "of", "is", "be", "do", "not", "and", "or", "for", "with", "please");

    private TextNormalizer() {
    }

    /** NFKC + lowercase + separators collapsed to single spaces. */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        return SEPARATOR.matcher(nfkc).replaceAll(" ").trim().toLowerCase(Locale.ROOT);
    }

    /** Normalized tokens with stop words removed, order discarded. */
    public static Set<String> tokens(String raw) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : normalize(raw).split(" ")) {
            if (t.isBlank() || STOP_WORDS.contains(t)) {
                continue;
            }
            out.add(t);
        }
        return out;
    }

    /** Jaccard overlap of the two token sets, 0.0 (disjoint) .. 1.0 (identical). */
    public static double similarity(String left, String right) {
        return similarity(tokens(left), tokens(right));
    }

    public static double similarity(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        if (intersection.isEmpty()) {
            return 0.0;
        }
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return (double) intersection.size() / union.size();
    }

    /**
     * Identity of a pattern. Category and scope are part of it on purpose: the
     * same sentence can be a global preference and a project-specific convention,
     * and collapsing those two would be wrong.
     */
    public static String fingerprint(String category, String scopeKey, String statement) {
        Set<String> tokens = tokens(statement);
        String canonical = category + "|" + scopeKey + "|" + String.join(" ", new java.util.TreeSet<>(tokens));
        return sha256(canonical).substring(0, 40);
    }

    /** True when the text reads like a prohibition rather than an instruction. */
    public static boolean isNegated(String raw) {
        String n = normalize(raw);
        return Arrays.stream(new String[]{"말 것", "말것", "하지", "금지", "안 되", "안되", "없이", "never", "not ", "avoid", "don t", "dont"})
                .anyMatch(marker -> n.contains(normalize(marker)));
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available on every JVM", e);
        }
    }
}
