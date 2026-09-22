package kr.co.page1.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import kr.co.page1.knowledge.domain.PatternCategory;
import kr.co.page1.knowledge.domain.PatternScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** No collaborators, so no Spring context - this is pure parsing. */
class MarkdownImportServiceTest {

    private final MarkdownImportService service = new MarkdownImportService();
    private final List<String> skipped = new ArrayList<>();

    private List<ObserveCommand> parse(String markdown) {
        return service.parse(markdown, PatternCategory.PREFERENCE, PatternScope.GLOBAL,
                null, "test.md", true, skipped);
    }

    @Test
    @DisplayName("'규칙 -- NOT: 금지' 관행을 statement / antiPattern 으로 쪼갠다")
    void splitsTheNotConvention() {
        List<ObserveCommand> parsed = parse("""
                - Gradle 검증은 짧게 끝낼 것 -- NOT: 매번 오래 걸리는 빌드로 기다리게 하지 말 것
                """);

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).statement()).isEqualTo("Gradle 검증은 짧게 끝낼 것");
        assertThat(parsed.get(0).antiPattern()).isEqualTo("매번 오래 걸리는 빌드로 기다리게 하지 말 것");
    }

    @Test
    @DisplayName("heading 으로 카테고리를 결정하고, 못 읽으면 기본값을 유지한다")
    void resolvesCategoryFromHeading() {
        List<ObserveCommand> parsed = parse("""
                # 우리 규칙

                - 기본 카테고리로 들어갈 항목이다

                ## Conventions

                - 커밋 메시지는 명령형으로 쓸 것

                ## Pitfalls

                - 32비트 JDK 8 로는 Boot 3 플러그인이 해석되지 않는다
                """);

        assertThat(parsed).hasSize(3);
        assertThat(parsed.get(0).category()).isEqualTo(PatternCategory.PREFERENCE);
        assertThat(parsed.get(1).category()).isEqualTo(PatternCategory.CONVENTION);
        assertThat(parsed.get(2).category()).isEqualTo(PatternCategory.PITFALL);
    }

    @Test
    @DisplayName("이 서버가 생성한 형식을 그대로 다시 읽어들인다 (라운드트립)")
    void roundTripsTheGeneratedFormat() {
        List<ObserveCommand> parsed = parse("""
                ## Conventions

                ### CONV-12 · PR 제목은 70자 이내로 쓸 것

                - **Rule**: PR 제목은 70자 이내로 쓸 것
                - **NOT**: 제목에 상세 설명을 넣지 말 것
                - **Why**: 목록 화면에서 잘려서 읽히지 않는다
                - **Evidence**: hits 4 · confidence 0.90 · ACTIVE
                - **Scope**: global · tags: pr
                """);

        assertThat(parsed).hasSize(1);
        ObserveCommand command = parsed.get(0);
        assertThat(command.category()).isEqualTo(PatternCategory.CONVENTION);
        assertThat(command.statement()).isEqualTo("PR 제목은 70자 이내로 쓸 것");
        assertThat(command.antiPattern()).isEqualTo("제목에 상세 설명을 넣지 말 것");
        assertThat(command.rationale()).isEqualTo("목록 화면에서 잘려서 읽히지 않는다");
    }

    @Test
    @DisplayName("산문과 코드블록은 무시한다 - 문단을 규칙으로 들이면 지식베이스가 오염된다")
    void ignoresProseAndCodeBlocks() {
        List<ObserveCommand> parsed = parse("""
                이 문서는 우리 팀의 작업 규칙을 설명한다. 이 문장은 규칙이 아니다.

                ```bash
                - 이것은 코드블록 안이므로 규칙이 아니다
                ```

                - 실제 규칙은 이 항목뿐이다
                """);

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).statement()).isEqualTo("실제 규칙은 이 항목뿐이다");
    }

    @Test
    @DisplayName("너무 짧거나 너무 긴 항목은 건너뛰고, 나머지 import 는 계속된다")
    void skipsUnusableItemsWithoutAbortingTheImport() {
        String tooLong = "가".repeat(ObserveCommand.MAX_STATEMENT + 50);
        List<ObserveCommand> parsed = parse("""
                - TODO
                - %s
                - 이 항목은 정상적으로 들어와야 한다
                """.formatted(tooLong));

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).statement()).isEqualTo("이 항목은 정상적으로 들어와야 한다");
        assertThat(skipped).hasSize(2);
    }

    @Test
    @DisplayName("번호 목록과 * 목록도 항목으로 인식한다")
    void acceptsOtherBulletStyles() {
        List<ObserveCommand> parsed = parse("""
                1. 첫 번째 규칙은 번호 목록이다
                * 두 번째 규칙은 별표 목록이다
                + 세 번째 규칙은 플러스 목록이다
                """);

        assertThat(parsed).hasSize(3);
    }

    @Test
    void emptyInputYieldsNothing() {
        assertThat(parse("")).isEmpty();
        assertThat(parse("   \n\n  ")).isEmpty();
    }
}
