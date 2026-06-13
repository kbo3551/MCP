package dev.codex.dbmcp.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MaskingServiceTest {

    private final MaskingService maskingService = new MaskingService();

    @Test
    void masksConfiguredSensitiveColumnPatterns() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("USER_PASSWORD_HASH", "hash");
        row.put("API_KEY", "key");
        row.put("EMAIL_ADDRESS", "user@example.com");
        row.put("MOBILE_NO", "01012345678");
        row.put("REG_NO", "secret");
        row.put("DISPLAY_NAME", "Kim");
        row.put("PHONE", null);

        Map<String, Object> masked = maskingService.maskRow(row);

        assertThat(masked)
                .containsEntry("USER_PASSWORD_HASH", "******")
                .containsEntry("API_KEY", "******")
                .containsEntry("EMAIL_ADDRESS", "******")
                .containsEntry("MOBILE_NO", "******")
                .containsEntry("REG_NO", "******")
                .containsEntry("DISPLAY_NAME", "Kim")
                .containsEntry("PHONE", null);
    }

    @Test
    void doesNotMaskUnrelatedPartialWords() {
        assertThat(maskingService.isSensitive("SECRETARY_NAME")).isFalse();
        assertThat(maskingService.isSensitive("TELEMETRY")).isFalse();
    }
}
