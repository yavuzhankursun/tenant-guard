package dev.tenantguard.shapes;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tenantguard.DifferentialProbe;
import dev.tenantguard.DifferentialProbe.Endpoint;
import dev.tenantguard.DifferentialProbe.Verdict;
import dev.tenantguard.Finding;
import dev.tenantguard.Finding.Rule;
import dev.tenantguard.Observation;
import dev.tenantguard.RowRead;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The text-column behaviour, asserted for every tenant column type and table location: an
 * unfiltered read as the attacker is an {@code ACTOR_MISMATCH} and a {@code MULTI_TENANT_STATEMENT},
 * a filtered read is silent, and the differential probe calls them LEAK and CLEAN.
 */
class TenantColumnShapesTest extends ShapesTestBase {

    @ParameterizedTest
    @MethodSource("tenantIdShapes")
    void unfilteredReadAsAttackerIsAnActorMismatch(Shape shape) {
        seedVictim(shape);

        guard.runAs(shape.attacker(), () -> unfilteredSum(shape));

        assertThat(guard.findings(Rule.ACTOR_MISMATCH)).singleElement().satisfies(f -> {
            assertThat(f.table()).isEqualTo(shape.label());
            assertThat(f.tenants()).containsExactly(shape.victim());
        });
        assertThat(guard.rowReads()).containsOnlyKeys(
                new RowRead(shape.label(), shape.victim()), new RowRead(shape.label(), shape.attacker()));
    }

    @ParameterizedTest
    @MethodSource("tenantIdShapes")
    void unfilteredReadWithoutActorIsAMultiTenantStatement(Shape shape) {
        seedVictim(shape);

        unfilteredSum(shape);

        assertThat(guard.findings()).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo(Rule.MULTI_TENANT_STATEMENT);
            assertThat(f.table()).isEqualTo(shape.label());
            assertThat(f.tenants()).containsExactlyInAnyOrder(shape.victim(), shape.attacker());
        });
    }

    /** The equality on the tenant column is leakproof for every type here, so it runs before the observer. */
    @ParameterizedTest
    @MethodSource("tenantIdShapes")
    void filteredReadAsAttackerIsSilent(Shape shape) {
        seedVictim(shape);

        guard.runAs(shape.attacker(), () -> filteredSum(shape, shape.attacker()));

        assertThat(guard.findings()).isEmpty();
        assertThat(guard.observations()).flatExtracting(o -> o.tenantsByTable().values())
                .allSatisfy(tenants -> assertThat(tenants).containsOnly(shape.attacker()));
    }

    @ParameterizedTest
    @MethodSource("tenantIdShapes")
    void differentialProbeCallsTheUnfilteredReadALeakAndTheFilteredOneClean(Shape shape) {
        DifferentialProbe probe = DifferentialProbe.recordBaseline(guard, shape.attacker(), List.of(
                new Endpoint("unfiltered", () -> unfilteredSum(shape)),
                new Endpoint("filtered", () -> filteredSum(shape, shape.attacker()))));
        seedVictim(shape);

        List<DifferentialProbe.Result> results = probe.compare();

        assertThat(results).extracting(DifferentialProbe.Result::verdict).containsExactly(Verdict.LEAK, Verdict.CLEAN);
        assertThat(results.getFirst().observerFindings()).extracting(Finding::table).contains(shape.label());
    }

    @Test
    void rowReadsCarryPostgresCanonicalTextForm() {
        // Non-canonical spellings on the way in: Postgres stores the value, ::text prints it canonically.
        seedVictim(UUID, UUID_A.toUpperCase(Locale.ROOT));
        seedVictim(BIGINT, "+0009000000001");
        seedVictim(INT, "  0001");

        guard.runAs("nobody", () -> List.of(UUID, BIGINT, INT).forEach(this::unfilteredSum));

        assertThat(guard.rowReads().keySet()).contains(
                new RowRead("t_uuid", UUID_A), new RowRead("t_bigint", "9000000001"), new RowRead("t_int", "1"));
    }

    /**
     * Normalization rule: none. The actor is compared with {@code tenant_id::text} exactly, so a
     * non-canonical actor never matches silently; it reports the attacker's own rows as foreign,
     * which is loud. Normalizing would be wrong for text columns, where "02" and "2" are two tenants.
     */
    @Test
    void nonCanonicalIntegerActorMatchesNothingAndReportsItsOwnRows() {
        guard.runAs("02", () -> unfilteredSum(INT));

        assertThat(guard.findings(Rule.ACTOR_MISMATCH)).singleElement()
                .satisfies(f -> assertThat(f.tenants()).containsExactly("2"));
    }

    @Test
    void upperCaseUuidActorMatchesNothingAndReportsItsOwnRows() {
        guard.runAs(UUID_B.toUpperCase(Locale.ROOT), () -> unfilteredSum(UUID));

        assertThat(guard.findings(Rule.ACTOR_MISMATCH)).singleElement()
                .satisfies(f -> assertThat(f.tenants()).containsExactly(UUID_B));
    }

    @Test
    void canonicalActorFromJavaValuesMatches() {
        guard.runAs(String.valueOf(2), () -> unfilteredSum(INT));
        guard.runAs(java.util.UUID.fromString(UUID_B.toUpperCase(Locale.ROOT)).toString(), () -> unfilteredSum(UUID));
        guard.runAs(String.valueOf(9000000002L), () -> unfilteredSum(BIGINT));

        assertThat(guard.findings()).isEmpty();
        assertThat(guard.observations()).extracting(Observation::tenantsByTable).containsExactly(
                Map.of("t_int", Set.of("2")), Map.of("t_uuid", Set.of(UUID_B)),
                Map.of("t_bigint", Set.of("9000000002")));
    }
}
