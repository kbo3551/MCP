package kr.co.page1.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TextNormalizerTest {

    @Test
    @DisplayName("같은 규칙을 다르게 띄어써도 같은 fingerprint")
    void fingerprintIgnoresPunctuationAndSpacing() {
        String a = TextNormalizer.fingerprint("PREFERENCE", "global", "Gradle 검증은 짧게 끝낼 것!");
        String b = TextNormalizer.fingerprint("PREFERENCE", "global", "gradle  검증은   짧게 끝낼것");
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("category 나 scope 가 다르면 같은 문장도 다른 fingerprint")
    void fingerprintIsScopedByCategoryAndScope() {
        String global = TextNormalizer.fingerprint("PREFERENCE", "global", "always run the build");
        String project = TextNormalizer.fingerprint("PREFERENCE", "project:alpha", "always run the build");
        String other = TextNormalizer.fingerprint("CONVENTION", "global", "always run the build");
        assertThat(global).isNotEqualTo(project);
        assertThat(global).isNotEqualTo(other);
    }

    @Test
    void similarityIsHighForReworded() {
        double score = TextNormalizer.similarity(
                "Spring Boot 앱 기동은 사용자가 직접 한다",
                "Spring Boot 앱 기동은 사용자가 한다");
        assertThat(score).isGreaterThan(0.6);
    }

    @Test
    void similarityIsZeroForUnrelated() {
        assertThat(TextNormalizer.similarity("gradle 빌드 검증", "결재선 지정 화면")).isZero();
    }

    @Test
    @DisplayName("금지문과 지시문을 구분한다 - 모순 탐지의 근거")
    void detectsNegation() {
        assertThat(TextNormalizer.isNegated("force push 하지 말 것")).isTrue();
        assertThat(TextNormalizer.isNegated("never push to main")).isTrue();
        assertThat(TextNormalizer.isNegated("feature 브랜치로 push 할 것")).isFalse();
    }
}
