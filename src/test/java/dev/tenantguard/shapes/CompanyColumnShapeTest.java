package dev.tenantguard.shapes;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tenantguard.DifferentialProbe;
import dev.tenantguard.DifferentialProbe.Endpoint;
import dev.tenantguard.DifferentialProbe.Verdict;
import dev.tenantguard.Finding.Rule;
import dev.tenantguard.junit.ObserverSetup;
import dev.tenantguard.junit.TenantIsolationTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tenant column under another name. Runs after (or before) the {@code tenant_id} classes in the same
 * database, so the probe table they created must not tie the observer to their column name.
 */
@TenantIsolationTest(tenantColumn = "company_id")
class CompanyColumnShapeTest extends ShapesTestBase {

    @Test
    void discoveryFindsOnlyTheCompanyTable(ObserverSetup setup) {
        assertThat(setup.tenantColumn()).isEqualTo("company_id");
        assertThat(setup.installedTables()).containsExactly(COMPANY.label());
        assertThat(setup.selfChecked()).isTrue();
    }

    @Test
    void observerPassiveAndDiffMatchTheTenantIdBehaviour() {
        DifferentialProbe probe = DifferentialProbe.recordBaseline(guard, COMPANY.attacker(), List.of(
                new Endpoint("unfiltered", () -> unfilteredSum(COMPANY)),
                new Endpoint("filtered", () -> filteredSum(COMPANY, COMPANY.attacker()))));
        seedVictim(COMPANY);

        assertThat(probe.compare()).extracting(DifferentialProbe.Result::verdict)
                .containsExactly(Verdict.LEAK, Verdict.CLEAN);
        assertThat(guard.findings(Rule.ACTOR_MISMATCH)).singleElement().satisfies(f -> {
            assertThat(f.table()).isEqualTo("t_company");
            assertThat(f.tenants()).containsExactly(COMPANY.victim());
        });
        assertThat(guard.findings(Rule.MULTI_TENANT_STATEMENT)).singleElement()
                .satisfies(f -> assertThat(f.tenants()).containsExactlyInAnyOrder("1", "2"));
    }
}
