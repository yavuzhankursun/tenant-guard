package dev.tenantguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IdentifiersQuotingTest {

    @Test
    void quotesMixedCaseReservedAndUnicodeNames() {
        assertThat(Identifiers.quote("tenant_id")).isEqualTo("\"tenant_id\"");
        assertThat(Identifiers.quote("MixedCase")).isEqualTo("\"MixedCase\"");
        assertThat(Identifiers.quote("order")).isEqualTo("\"order\"");
        assertThat(Identifiers.quote("müşteri$1")).isEqualTo("\"müşteri$1\"");
    }

    @Test
    void qualifiedNamesQuoteEachPart() {
        assertThat(Identifiers.quoteQualified("invoice")).isEqualTo("\"invoice\"");
        assertThat(Identifiers.quoteQualified("tenancy.MixedCase")).isEqualTo("\"tenancy\".\"MixedCase\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a\"b", "a'b", "a|b", "a.b", "odd name", "a\u0000b", "a\nb", "1abc", "x;drop table y"})
    void rejectsQuotesControlCharactersDelimitersAndSpaces(String name) {
        assertThatThrownBy(() -> Identifiers.quote(name)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullOverlongAndOverQualifiedNames() {
        assertThatThrownBy(() -> Identifiers.quote(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Identifiers.quote("a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        // 32 two-byte characters: 64 bytes, over Postgres' 63-byte NAMEDATALEN limit (silently truncated otherwise).
        assertThatThrownBy(() -> Identifiers.quote("ş".repeat(32))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Identifiers.quoteQualified("a.b.c")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Identifiers.quoteQualified(".b")).isInstanceOf(IllegalArgumentException.class);
    }
}
