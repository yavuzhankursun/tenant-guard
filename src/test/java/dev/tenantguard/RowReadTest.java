package dev.tenantguard;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tenantguard.RowRead.Kind;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Notice parsing is the trust boundary: every string that reaches a NOTICE (the app's own, or a
 * tenant-controlled value the app raises) passes through here. Edge cases are pinned one per test
 * so a mutated boundary (prefix, field count, kind code, nonce match) fails exactly one of them.
 */
class RowReadTest {

    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final String OTHER = "fedcba9876543210fedcba9876543210";

    private static Optional<RowRead> parse(String message) {
        return RowRead.parse(new SQLWarning(message), NONCE::equals);
    }

    @Test
    void parsesAReadNotice() {
        assertThat(parse("tg:" + NONCE + "|r|invoice|A")).contains(new RowRead("invoice", "A", Kind.READ));
    }

    @Test
    void parsesAWriteNotice() {
        assertThat(parse("tg:" + NONCE + "|w|invoice|A")).contains(new RowRead("invoice", "A", Kind.WRITE));
    }

    @Test
    void theTwoArgumentConstructorIsARead() {
        assertThat(new RowRead("invoice", "A")).isEqualTo(new RowRead("invoice", "A", Kind.READ));
        assertThat(RowRead.write("invoice", "A")).isEqualTo(new RowRead("invoice", "A", Kind.WRITE));
    }

    @Test
    void findsThePrefixAfterADriverSuppliedLead() {
        assertThat(parse("NOTICE: tg:" + NONCE + "|r|invoice_line|B ")).contains(new RowRead("invoice_line", "B"));
    }

    @Test
    void keepsPipesInsideTheTenantValue() {
        assertThat(parse("tg:" + NONCE + "|r|invoice|acme|eu")).contains(new RowRead("invoice", "acme|eu"));
    }

    @Test
    void keepsAnEmptyTenantValue() {
        assertThat(parse("tg:" + NONCE + "|r|invoice|")).contains(new RowRead("invoice", ""));
    }

    @Test
    void ignoresTheUnsignedPreNonceFormat() {
        // What the observer raised before nonces, and what an app's own RAISE NOTICE 'tg|...' looks like.
        assertThat(parse("tg|invoice|A")).isEmpty();
    }

    @Test
    void ignoresAnUntrustedNonce() {
        assertThat(parse("tg:" + OTHER + "|r|invoice|A")).isEmpty();
    }

    @Test
    void ignoresATruncatedNonce() {
        assertThat(parse("tg:" + NONCE.substring(1) + "|r|invoice|A")).isEmpty();
    }

    @Test
    void ignoresANonceWithATrailingCharacter() {
        assertThat(parse("tg:" + NONCE + "0|r|invoice|A")).isEmpty();
    }

    @Test
    void ignoresAnEmptyNonceEvenWhenEverythingIsTrusted() {
        assertThat(RowRead.parse(new SQLWarning("tg:|r|invoice|A"), nonce -> true)).isEmpty();
    }

    @Test
    void ignoresAnUnknownKind() {
        assertThat(parse("tg:" + NONCE + "|x|invoice|A")).isEmpty();
    }

    @Test
    void kindCodesAreCaseSensitive() {
        assertThat(parse("tg:" + NONCE + "|R|invoice|A")).isEmpty();
        assertThat(parse("tg:" + NONCE + "|W|invoice|A")).isEmpty();
    }

    @Test
    void ignoresAnEmptyKind() {
        assertThat(parse("tg:" + NONCE + "||invoice|A")).isEmpty();
    }

    @Test
    void ignoresAKindLongerThanOneCode() {
        assertThat(parse("tg:" + NONCE + "|rw|invoice|A")).isEmpty();
    }

    @Test
    void ignoresAnEmptyTable() {
        assertThat(parse("tg:" + NONCE + "|r||A")).isEmpty();
    }

    @Test
    void ignoresTheApplicationsOwnWarnings() {
        assertThat(parse("app|invoice|A")).isEmpty();
    }

    @Test
    void ignoresAWarningWithoutMessage() {
        assertThat(RowRead.parse(new SQLWarning((String) null), nonce -> true)).isEmpty();
    }

    @Test
    void ignoresANoticeWithoutTenant() {
        assertThat(parse("tg:" + NONCE + "|r|invoice")).isEmpty();
    }

    @Test
    void ignoresANoticeWithoutKind() {
        // The pre-write format with a nonce: two fields after it, not three.
        assertThat(parse("tg:" + NONCE + "|invoice|A")).isEmpty();
    }

    @Test
    void ignoresTheBarePrefix() {
        assertThat(parse("tg:")).isEmpty();
        assertThat(parse("tg:" + NONCE)).isEmpty();
        assertThat(parse("tg:" + NONCE + "|")).isEmpty();
    }

    @Test
    void onlyTheNonceIsPassedToTheTrustCheck() {
        List<String> asked = new ArrayList<>();
        Optional<RowRead> parsed = RowRead.parse(new SQLWarning("tg:" + NONCE + "|r|invoice|A"), nonce -> {
            asked.add(nonce);
            return false;
        });

        assertThat(asked).containsExactly(NONCE);
        assertThat(parsed).isEmpty();
    }
}
