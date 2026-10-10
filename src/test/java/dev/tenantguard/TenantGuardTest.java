package dev.tenantguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.tenantguard.Finding.Rule;
import dev.tenantguard.ObserverInstaller.Installation;
import dev.tenantguard.TenantGuard.HarvestPoint;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TenantGuardTest {

    private static final RowRead A = new RowRead("invoice", "A");
    private static final RowRead B = new RowRead("invoice", "B");
    private static final RowRead WRITE_A = RowRead.write("invoice", "A");
    private static final RowRead WRITE_B = RowRead.write("invoice", "B");
    private static final String N1 = "11111111111111111111111111111111";
    private static final String N2 = "22222222222222222222222222222222";
    private static final String N3 = "33333333333333333333333333333333";

    private final TenantGuard guard = new TenantGuard();

    @Test
    void anEmptyHarvestRecordsNothing() {
        guard.record("SELECT 1", List.of(), HarvestPoint.EXECUTE);

        assertThat(guard.observations()).isEmpty();
        assertThat(guard.harvestedRows()).isEmpty();
    }

    @Test
    void countsEveryReadAndTagsTheActor() {
        guard.runAs("B", () -> guard.record("q", List.of(A, A, B), HarvestPoint.FETCH));

        assertThat(guard.harvestedRows()).containsOnly(entry(HarvestPoint.FETCH, 3L));
        assertThat(guard.rowReads()).containsOnly(entry(A, 2L), entry(B, 1L));
        assertThat(guard.observations()).singleElement().satisfies(o -> assertThat(o.actor()).isEqualTo("B"));
        assertThat(guard.findings(Finding.Rule.ACTOR_MISMATCH)).hasSize(1);
    }

    @Test
    void callAsRestoresThePreviousActor() {
        guard.runAs("B", () -> guard.runAs("C", () -> { }));
        guard.record("passive", List.of(A), HarvestPoint.EXECUTE);

        assertThat(guard.observations()).singleElement().satisfies(o -> assertThat(o.actor()).isNull());
    }

    @Test
    void callAsRestoresTheActorWhenTheActionThrows() {
        assertThatThrownBy(() -> guard.runAs("B", () -> {
            throw new IllegalStateException("boom");
        })).hasMessage("boom");
        guard.record("passive", List.of(A), HarvestPoint.EXECUTE);

        assertThat(guard.findings()).as("no actor left behind, so no ACTOR_MISMATCH").isEmpty();
    }

    @Test
    void trustsNothingUntilAnInstallationIsTrusted() {
        assertThat(guard.trusts(N1)).isFalse();
        assertThat(guard.trusts("")).isFalse();
        assertThat(guard.trusts(null)).isFalse();
    }

    @Test
    void trustsTheCurrentNonceOfEveryObservedDatabase() {
        guard.trust(new Installation("db1", N1));
        guard.trust(new Installation("db2", N2));

        assertThat(guard.trusts(N1)).isTrue();
        assertThat(guard.trusts(N2)).isTrue();
        assertThat(guard.trusts(N3)).isFalse();
    }

    @Test
    void aReinstallReplacesTheNonceOfThatDatabaseOnly() {
        guard.trust(new Installation("db1", N1));
        guard.trust(new Installation("db2", N2));
        guard.trust(new Installation("db1", N3));

        assertThat(guard.trusts(N1)).as("replaced: the function no longer raises it").isFalse();
        assertThat(guard.trusts(N2)).isTrue();
        assertThat(guard.trusts(N3)).isTrue();
    }

    @Test
    void resetKeepsTheTrustedNonces() {
        guard.trust(new Installation("db1", N1));
        guard.reset();

        assertThat(guard.trusts(N1)).isTrue();
    }

    @Test
    void aWriteOfAForeignTenantIsAWriteMismatch() {
        guard.runAs("B", () -> guard.record("INSERT", List.of(WRITE_A), HarvestPoint.EXECUTE));

        assertThat(guard.findings()).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo(Rule.WRITE_MISMATCH);
            assertThat(f.table()).isEqualTo("invoice");
            assertThat(f.tenants()).containsExactly("A");
            assertThat(f.sql()).isEqualTo("INSERT");
        });
    }

    @Test
    void aWriteOfTheActorsOwnTenantIsNoFinding() {
        guard.runAs("B", () -> guard.record("INSERT", List.of(WRITE_B, B), HarvestPoint.EXECUTE));

        assertThat(guard.findings()).isEmpty();
        assertThat(guard.observations()).singleElement().satisfies(o -> {
            assertThat(o.tenantsByTable()).containsOnly(entry("invoice", Set.of("B")));
            assertThat(o.writtenTenantsByTable()).containsOnly(entry("invoice", Set.of("B")));
        });
    }

    @Test
    void aWriteWithoutActorIsObservedButNotJudged() {
        guard.record("INSERT", List.of(WRITE_A, WRITE_B), HarvestPoint.EXECUTE);

        assertThat(guard.findings()).as("passive mode has no actor to compare a write with").isEmpty();
        assertThat(guard.observations()).singleElement()
                .satisfies(o -> assertThat(o.writtenTenantsByTable()).containsOnly(entry("invoice", Set.of("A", "B"))));
    }

    @Test
    void writesAndReadsAreSeparateFacts() {
        // UPDATE of a foreign row: USING reads it, WITH CHECK writes it back.
        guard.runAs("B", () -> guard.record("UPDATE", List.of(A, WRITE_A), HarvestPoint.EXECUTE));

        assertThat(guard.findings()).extracting(Finding::rule)
                .containsExactlyInAnyOrder(Rule.ACTOR_MISMATCH, Rule.WRITE_MISMATCH);
        assertThat(guard.rowReads()).containsOnly(entry(A, 1L), entry(WRITE_A, 1L));
    }

    @Test
    void writesDoNotMakeAStatementMultiTenant() {
        // Only reads count for the passive rule: one row read, one other row written.
        guard.record("INSERT ... SELECT", List.of(A, WRITE_B), HarvestPoint.EXECUTE);

        assertThat(guard.findings(Rule.MULTI_TENANT_STATEMENT)).isEmpty();
    }

    @Test
    void onlyForeignWrittenTenantsAreReported() {
        guard.runAs("B", () -> guard.record("INSERT", List.of(WRITE_A, WRITE_B), HarvestPoint.EXECUTE));

        assertThat(guard.findings(Rule.WRITE_MISMATCH)).singleElement()
                .satisfies(f -> assertThat(f.tenants()).containsExactly("A"));
    }

    @Test
    void anInstallationRefusesAMalformedNonce() {
        for (String nonce : new String[] {"", "xyz", N1.substring(1), N1 + "1", N1.toUpperCase().replace('1', 'A')}) {
            assertThatIllegalArgumentException().as(nonce).isThrownBy(() -> new Installation("db", nonce));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new Installation("db", null));
        assertThatIllegalArgumentException().isThrownBy(() -> new Installation(null, N1));
    }

    @Test
    void anInstallationDoesNotPrintItsNonce() {
        assertThat(new Installation("db1", N1).toString()).contains("db1").doesNotContain(N1);
    }
}
