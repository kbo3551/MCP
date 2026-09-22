package kr.co.page1.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import kr.co.page1.knowledge.domain.PatternCategory;
import kr.co.page1.knowledge.domain.PatternScope;
import kr.co.page1.knowledge.domain.PatternStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pattern-service-test;DB_CLOSE_DELAY=-1",
        "knowledge.sync.on-write=false",
        "knowledge.inject.on-initialize=false"
})
class PatternServiceTest {

    @Autowired
    private PatternService service;

    @Autowired
    private ContextBundleService bundles;

    private ObserveCommand command(String statement) {
        return new ObserveCommand(PatternCategory.PREFERENCE, PatternScope.GLOBAL, null,
                statement, null, null, null, null, "test", null, false);
    }

    @Test
    @DisplayName("한 번만 본 것은 OBSERVED - 규칙으로 주입되지 않는다")
    void firstSightingIsNotARule() {
        PatternService.Outcome outcome = service.observe(command("keep the release notes in English"));

        assertThat(outcome.action()).isEqualTo(PatternService.Action.CREATED);
        assertThat(outcome.pattern().getStatus()).isEqualTo(PatternStatus.OBSERVED);
        assertThat(outcome.pattern().getHitCount()).isEqualTo(1);
        assertThat(outcome.becameInjectable()).isFalse();
    }

    @Test
    @DisplayName("같은 문장을 세 번 보면 ACTIVE 로 승격되고 행은 하나만 남는다")
    void thirdSightingPromotesToActive() {
        String statement = "run npm ci instead of npm install in CI";
        service.observe(command(statement));
        PatternService.Outcome second = service.observe(command(statement));
        PatternService.Outcome third = service.observe(command(statement));

        assertThat(second.pattern().getStatus()).isEqualTo(PatternStatus.CANDIDATE);
        assertThat(third.pattern().getStatus()).isEqualTo(PatternStatus.ACTIVE);
        assertThat(third.action()).isEqualTo(PatternService.Action.MERGED);
        assertThat(third.pattern().getHitCount()).isEqualTo(3);
        assertThat(third.becameInjectable()).isTrue();

        // one row, not three (filtered exactly: search is fuzzy by design)
        assertThat(service.search(statement, null, null, null, null, 50).stream()
                .filter(hit -> hit.pattern().getStatement().equals(statement))
                .count()).isEqualTo(1);
    }

    @Test
    @DisplayName("살짝 다르게 표현한 같은 규칙은 새 행이 아니라 기존 행에 합쳐진다")
    void rewordedSightingMergesIntoExistingPattern() {
        PatternService.Outcome first = service.observe(command("always run the linter before committing"));
        PatternService.Outcome reworded = service.observe(command("always run the linter before committing code"));

        assertThat(reworded.action()).isEqualTo(PatternService.Action.MERGED);
        assertThat(reworded.pattern().getId()).isEqualTo(first.pattern().getId());
        assertThat(reworded.similarity()).isLessThan(1.0).isGreaterThanOrEqualTo(0.82);
        assertThat(reworded.pattern().getHitCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("category 가 다르면 같은 문장도 별개 규칙")
    void sameStatementInAnotherCategoryStaysSeparate() {
        String statement = "document every public endpoint";
        PatternService.Outcome preference = service.observe(command(statement));
        PatternService.Outcome convention = service.observe(new ObserveCommand(
                PatternCategory.CONVENTION, PatternScope.GLOBAL, null, statement,
                null, null, null, null, "test", null, false));

        assertThat(convention.action()).isEqualTo(PatternService.Action.CREATED);
        assertThat(convention.pattern().getId()).isNotEqualTo(preference.pattern().getId());
    }

    @Test
    @DisplayName("confirm 은 한 번만 본 것도 즉시 ACTIVE 로 올린다")
    void confirmPromotesImmediately() {
        PatternService.Outcome outcome = service.observe(command("prefer tabs over spaces in makefiles"));
        assertThat(outcome.pattern().getStatus()).isEqualTo(PatternStatus.OBSERVED);

        var confirmed = service.confirm(outcome.pattern().getId(), "boryeong");

        assertThat(confirmed.getStatus()).isEqualTo(PatternStatus.ACTIVE);
        assertThat(confirmed.getConfidence()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("PROJECT 규칙은 해당 프로젝트를 요청할 때만 번들에 들어간다")
    void projectRulesAreScoped() {
        ObserveCommand scoped = new ObserveCommand(PatternCategory.TOOLING, PatternScope.PROJECT, "alpha",
                "alpha 프로젝트는 gradlew.bat 으로만 빌드할 것", null, null, null, null, "test", null, true);
        service.observe(scoped);

        String forAlpha = bundles.build(new ContextBundleService.BundleRequest(
                "alpha", Set.of(), null, 4000, false)).markdown();
        String forBeta = bundles.build(new ContextBundleService.BundleRequest(
                "beta", Set.of(), null, 4000, false)).markdown();

        assertThat(forAlpha).contains("gradlew.bat");
        assertThat(forBeta).doesNotContain("gradlew.bat");
    }

    @Test
    @DisplayName("archive 한 규칙은 더 이상 주입되지 않는다")
    void archivedRulesLeaveTheBundle() {
        PatternService.Outcome outcome = service.observe(new ObserveCommand(
                PatternCategory.PITFALL, PatternScope.GLOBAL, null,
                "레거시 mysql 5.1 드라이버는 timezone 을 물어본다", null, null, null, null, "test", null, true));

        assertThat(bundles.build(new ContextBundleService.BundleRequest(null, Set.of(), null, 8000, false))
                .markdown()).contains("timezone");

        service.archive(outcome.pattern().getId(), "드라이버를 8.x 로 올려서 무효", null);

        assertThat(bundles.build(new ContextBundleService.BundleRequest(null, Set.of(), null, 8000, false))
                .markdown()).doesNotContain("timezone");
    }

    @Test
    @DisplayName("문단을 규칙으로 밀어넣으면 조용히 잘리지 않고 거부된다")
    void overlongStatementIsRejectedNotTruncated() {
        String paragraph = "규칙이라기보다 설명에 가까운 긴 문장. ".repeat(20);
        assertThat(paragraph.length()).isGreaterThan(ObserveCommand.MAX_STATEMENT);

        assertThatThrownBy(() -> service.observe(command(paragraph)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit is " + ObserveCommand.MAX_STATEMENT);
    }

    @Test
    @DisplayName("여러 줄로 들어온 규칙은 한 줄로 정규화된다")
    void multilineStatementIsCollapsed() {
        PatternService.Outcome outcome = service.observe(command("""
                커밋 메시지는
                   명령형으로   쓸 것"""));

        assertThat(outcome.pattern().getStatement()).isEqualTo("커밋 메시지는 명령형으로 쓸 것");
    }

    @Test
    @DisplayName("빈 문장과 PROJECT 스코프인데 projectKey 없는 경우를 거부한다")
    void rejectsUnusableCommands() {
        assertThatThrownBy(() -> command("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("statement is required");

        assertThatThrownBy(() -> new ObserveCommand(PatternCategory.TOOLING, PatternScope.PROJECT, null,
                "프로젝트 스코프인데 키가 없다", null, null, null, null, "test", null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectKey is required");
    }

    @Test
    @DisplayName("방금 기록한 규칙은 정리 후보에 오르지 않는다 - 비교 방향이 뒤집혔는지 잡는 테스트")
    void freshRulesAreNotStale() {
        service.observe(command("방금 관찰한 규칙은 오래된 것이 아니다"));

        PatternService.Review review = service.review(null, 1);

        assertThat(review.staleDays()).isEqualTo(1);
        assertThat(review.total()).isZero();
    }
}
