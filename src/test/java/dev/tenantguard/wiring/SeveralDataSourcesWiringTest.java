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
 * {@code dataSources} names both: the observer is installed and self-checked through each, and a
 * leak through the reporting DataSource is reported like one through the primary. Same application
 * context as {@link SecondDataSourceWiringTest}: which DataSources are observed is per test class.
 */
@SpringBootTest(classes = TwoDataSourcesApp.class)
@TenantIsolationTest(tenantColumn = "tenant_id", tables = "invoice", dataSources = {"dataSource", "reporting"})
class SeveralDataSourcesWiringTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    @Qualifier("reporting")
    DataSource reporting;

    @Test
    void bothAreObservedAndNothingIsWarned(ObserverSetup setup) {
        assertThat(setup.warnings()).isEmpty();
        assertThat(setup.installedTables()).containsExactly("invoice");
        assertThat(setup.selfChecked()).isTrue();
    }

    @Test
    void aLeakyReadThroughTheReportingDataSourceIsObserved(TenantGuard guard) {
        guard.runAs("B",
                () -> new JdbcTemplate(reporting).queryForObject("SELECT sum(amount) FROM invoice", BigDecimal.class));

        WiringDb.assertLeakOfA(guard);
    }

    @Test
    void aLeakyReadThroughThePrimaryIsStillObserved(TenantGuard guard) {
        guard.runAs("B", () -> jdbc.queryForObject("SELECT sum(amount) FROM invoice", BigDecimal.class));

        WiringDb.assertLeakOfA(guard);
    }
}
