package dev.tenantguard.shapes;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tenantguard.Finding.Rule;
import dev.tenantguard.junit.ObserverSetup;
import dev.tenantguard.junit.TenantIsolationTest;
import org.junit.jupiter.api.Test;

/**
 * {@code schemas} on the annotation: discovery looks only there, and a schema off the search_path
 * is granted to the observed role, or the app's reads of it would fail with "permission denied".
 */
@TenantIsolationTest(tenantColumn = "tenant_id", schemas = "archive")
class ExplicitSchemaShapeTest extends ShapesTestBase {

    @Test
    void onlyTheNamedSchemaIsDiscovered(ObserverSetup setup) {
        assertThat(setup.installedTables()).containsExactly(ARCHIVED.label());
        assertThat(setup.selfChecked()).isTrue();
    }

    @Test
    void readsOffTheSearchPathAreObservedUnderTheQualifiedName() {
        seedVictim(ARCHIVED);

        guard.runAs(ARCHIVED.attacker(), () -> unfilteredSum(ARCHIVED));

        assertThat(guard.findings(Rule.ACTOR_MISMATCH)).singleElement().satisfies(f -> {
            assertThat(f.table()).isEqualTo("archive.t_archived");
            assertThat(f.tenants()).containsExactly(ARCHIVED.victim());
        });
    }
}
