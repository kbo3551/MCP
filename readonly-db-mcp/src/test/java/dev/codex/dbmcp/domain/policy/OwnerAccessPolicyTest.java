package dev.codex.dbmcp.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.codex.dbmcp.domain.exception.AccessDeniedException;
import java.util.List;
import org.junit.jupiter.api.Test;

class OwnerAccessPolicyTest {

    private final OwnerAccessPolicy policy =
            new OwnerAccessPolicy(List.of("APP", "REPORTING"));

    @Test
    void normalizesAndAllowsConfiguredOwner() {
        assertThat(policy.requireAllowed(" app ")).isEqualTo("APP");
    }

    @Test
    void rejectsUnconfiguredOwner() {
        assertThatThrownBy(() -> policy.requireAllowed("SYS"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void wildcardAllowsAnyValidOwner() {
        OwnerAccessPolicy wildcardPolicy =
                new OwnerAccessPolicy(List.of("*"));

        assertThat(wildcardPolicy.allOwnersAllowed()).isTrue();
        assertThat(wildcardPolicy.allowedOwners()).isEmpty();
        assertThat(wildcardPolicy.requireAllowed("SRSYSTEM")).isEqualTo("SRSYSTEM");
        assertThat(wildcardPolicy.requireAllowed("REPORTING")).isEqualTo("REPORTING");
        assertThat(wildcardPolicy.requireAllowed("*")).isEqualTo("*");
        assertThatThrownBy(() -> wildcardPolicy.requireConcreteOwner("*"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wildcardCannotBeMixedWithExplicitOwners() {
        assertThatThrownBy(() -> new OwnerAccessPolicy(List.of("*", "APP")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsIdentifierInjection() {
        assertThatThrownBy(() -> policy.requireAllowed("APP' OR '1'='1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.requireValidTableName("USERS; DROP TABLE USERS"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
