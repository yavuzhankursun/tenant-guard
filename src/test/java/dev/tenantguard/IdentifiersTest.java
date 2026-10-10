package dev.tenantguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IdentifiersTest {

    @Test
    void acceptsPlainLowerCaseIdentifiers() {
        assertThat(Identifiers.require("tenant_id")).isEqualTo("tenant_id");
    }

    @Test
    void rejectsNullAndAnythingThatWouldNeedQuoting() {
        assertThatThrownBy(() -> Identifiers.require(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Identifiers.require("invoice; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Identifiers.require("Invoice")).isInstanceOf(IllegalArgumentException.class);
    }
}
