package kr.co.page1.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * writeManagedBlock touches none of the injected collaborators, so it is tested
 * directly rather than through a Spring context - the behaviour under test is
 * "does a human's text survive regeneration", and that needs no database.
 */
class RuleMarkdownServiceTest {

    private final RuleMarkdownService service = new RuleMarkdownService(null, null, null);

    @Test
    @DisplayName("파일이 없으면 heading + 생성 블록으로 만든다")
    void createsFileWithHeading(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("preferences.md");
        service.writeManagedBlock(file, "# Preferences", "- rule one");

        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).startsWith("# Preferences");
        assertThat(content).contains(RuleMarkdownService.BEGIN_MARKER);
        assertThat(content).contains("- rule one");
        assertThat(content).contains(RuleMarkdownService.END_MARKER);
    }

    @Test
    @DisplayName("재생성해도 마커 밖의 수동 작성 내용은 보존된다")
    void preservesManualContentOutsideMarkers(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("preferences.md");
        service.writeManagedBlock(file, "# Preferences", "- generated v1");

        String withManualNote = Files.readString(file, StandardCharsets.UTF_8)
                + "\n## 사람이 쓴 메모\n이 문장은 살아남아야 한다.\n";
        Files.writeString(file, withManualNote, StandardCharsets.UTF_8);

        service.writeManagedBlock(file, "# Preferences", "- generated v2");

        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("이 문장은 살아남아야 한다.");
        assertThat(content).contains("- generated v2");
        assertThat(content).doesNotContain("- generated v1");
        assertThat(content).containsOnlyOnce(RuleMarkdownService.BEGIN_MARKER);
    }

    @Test
    @DisplayName("마커가 없는 기존 파일에는 덮어쓰지 않고 블록을 덧붙인다")
    void appendsBlockToUnmanagedFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("existing.md");
        Files.writeString(file, "# 기존 문서\n내용이 있다.\n", StandardCharsets.UTF_8);

        service.writeManagedBlock(file, "# Ignored", "- generated");

        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("내용이 있다.");
        assertThat(content).contains("- generated");
        assertThat(content).doesNotContain("# Ignored");
    }
}
