package dev.tenantguard.wiring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tenantguard.TenantGuard;
import dev.tenantguard.junit.ObserverSetup;
import dev.tenantguard.junit.TenantIsolationTest;
import java.math.BigDecimal;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Default {@code dataSources}: only the {@code @Primary} DataSource is observed. The second one is
 * not, and says so in {@link ObserverSetup#warnings()}; reads through it run as its own user, so they
 * neither fail on a database without the observer nor show up as findings.
 */
@SpringBootTest(classes = TwoDataSourcesApp.class)
@TenantIsolationTest(tenantColumn = "tenant_id", tables = "invoice")
class SecondDataSourceWiringTest {

    static final String WARNING = "DataSource 'reporting' is not observed: TenantGuard installs and self-checks "
            + "the observer only through [dataSource], and reads through 'reporting' run with the app's own role, "
            + "so they are never reported. Observe it too with "
            + "@TenantIsolationTest(dataSources = {\"dataSource\", \"reporting\"}).";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    @Qualifier("reporting")
    DataSource reporting;

    @Test
    void theSecondDataSourceIsReportedAsNotObserved(ObserverSetup setup) {
        assertThat(setup.warnings()).containsExactly(WARNING);
    }

    @Test
    void aLeakyReadThroughThePrimaryIsObserved(TenantGuard guard) {
        guard.runAs("B", () -> jdbc.queryForObject("SELECT sum(amount) FROM invoice", BigDecimal.class));

        WiringDb.assertLeakOfA(guard);
    }

    @Test
    void aLeakyReadThroughTheUnobservedOneWorksAndIsInvisibleAsTheWarningSays(TenantGuard guard) {
        BigDecimal total = guard.callAs("B",
                () -> new JdbcTemplate(reporting).queryForObject("SELECT sum(amount) FROM invoice", BigDecimal.class));

        assertThat(total).isEqualByComparingTo("600");
        assertThat(guard.observations()).isEmpty();
    }
}
