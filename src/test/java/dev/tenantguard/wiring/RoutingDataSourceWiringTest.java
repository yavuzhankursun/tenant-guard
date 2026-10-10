package dev.tenantguard.wiring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tenantguard.GuardedDataSource;
import dev.tenantguard.RowRead;
import dev.tenantguard.TenantGuard;
import dev.tenantguard.TenantSeeder;
import dev.tenantguard.junit.ObserverSetup;
import dev.tenantguard.junit.TenantIsolationTest;
import java.math.BigDecimal;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * An {@code AbstractRoutingDataSource} over two databases whose pools are not beans. The routing
 * bean is guarded as a whole, so every target's connections are; the extension installs the
 * observer and runs the self-check through every resolved target, not only through whichever one
 * the lookup key happens to pick during setup.
 */
@SpringBootTest(classes = RoutingDataSourceWiringTest.App.class)
@TestPropertySource(properties = "spring.flyway.enabled=false")
@TenantIsolationTest(tenantColumn = "tenant_id", tables = "invoice")
class RoutingDataSourceWiringTest {

    @JdbcApp
    static class App {

        @Bean
        DataSource dataSource() {
            DataSource a = WiringDb.pool(TenantRouting.A);
            TenantRouting routing = new TenantRouting();
            routing.setTargetDataSources(Map.of("A", a, "B", WiringDb.pool(TenantRouting.B)));
            routing.setDefaultTargetDataSource(a);
            return routing;
        }

        @Bean
        TenantSeeder seeder(JdbcTemplate jdbc) {
            return TenantRouting.seeder(jdbc);
        }
    }

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theRoutingBeanIsGuardedAndEveryTargetIsSelfChecked(ObserverSetup setup) throws Exception {
        assertThat(dataSource.isWrapperFor(GuardedDataSource.class)).isTrue();
        assertThat(setup.selfChecked()).isTrue();
        assertThat(setup.installedTables()).containsExactly("invoice");
        assertThat(setup.warnings()).isEmpty();
    }

    @Test
    void bRoutedToItsOwnDatabaseReadsOnlyItsOwnRowsAndIsObservedThere(TenantGuard guard) {
        BigDecimal total = guard.callAs("B", () -> TenantRouting.as("B",
                () -> jdbc.queryForObject("SELECT sum(amount) FROM invoice", BigDecimal.class)));

        assertThat(total).isEqualByComparingTo("300");
        assertThat(guard.findings()).isEmpty();
        assertThat(guard.rowReads()).as("target B is observed, not just the default target")
                .isEqualTo(Map.of(new RowRead("invoice", "B"), 2L));
    }

    @Test
    void bWithoutATenantContextFallsBackToAsDatabaseAndTheLeakIsObserved(TenantGuard guard) {
        BigDecimal total = guard.callAs("B", () -> jdbc.queryForObject("SELECT sum(amount) FROM invoice", BigDecimal.class));

        assertThat(total).isEqualByComparingTo("300");
        WiringDb.assertLeakOfA(guard);
    }
}
