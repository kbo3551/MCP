package kr.co.page1.knowledge.domain;

import java.util.Locale;

/**
 * What kind of knowledge a pattern carries. The category decides which markdown
 * file the rule is rendered into, so adding a value here adds a rules file.
 */
public enum PatternCategory {

    PREFERENCE("preferences.md", "Preferences", "사용자가 원하는 방식 / 말투 / 결과물 형태"),
    CONVENTION("conventions.md", "Conventions", "코딩 규칙, 네이밍, 파일 배치, 커밋 규약"),
    WORKFLOW("workflows.md", "Workflows", "작업 순서, 검증 방식, 릴리스 절차"),
    ARCHITECTURE("architecture.md", "Architecture", "구조적 결정과 그 이유"),
    DOMAIN("domain.md", "Domain Knowledge", "업무 용어, 데이터 의미, 시스템 간 관계"),
    PITFALL("pitfalls.md", "Pitfalls", "실제로 당해 본 함정과 회피법"),
    TOOLING("tooling.md", "Tooling", "빌드/실행/환경 관련 사실"),
    REQUIREMENT("requirements.md", "Requirements", "반복적으로 요구되는 기능/품질 조건");

    private final String fileName;
    private final String heading;
    private final String hint;

    PatternCategory(String fileName, String heading, String hint) {
        this.fileName = fileName;
        this.heading = heading;
        this.hint = hint;
    }

    public String fileName() {
        return fileName;
    }

    public String heading() {
        return heading;
    }

    public String hint() {
        return hint;
    }

    /** Lenient parse: accepts any case, hyphens, and the markdown file name. */
    public static PatternCategory parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return PREFERENCE;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(".md", "");
        for (PatternCategory c : values()) {
            if (c.name().toLowerCase(Locale.ROOT).equals(key)
                    || c.fileName.equals(raw.trim())
                    || c.fileName.replace(".md", "").equals(key)) {
                return c;
            }
        }
        // tolerate plurals: "preferences" -> PREFERENCE
        for (PatternCategory c : values()) {
            if (key.startsWith(c.name().toLowerCase(Locale.ROOT))) {
                return c;
            }
        }
        throw new IllegalArgumentException("unknown category: " + raw
                + " (allowed: PREFERENCE, CONVENTION, WORKFLOW, ARCHITECTURE, DOMAIN, PITFALL, TOOLING, REQUIREMENT)");
    }
}
