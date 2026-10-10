package dev.tenantguard.wiring;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Same routing, but each tenant's pool is a DataSource bean of its own, so both the targets and the
 * routing bean are guarded. A connection must still be observed once, not once per guard, and the
 * target beans are not "unobserved DataSources": the routing bean reaches them.
 */
@SpringBootTest(classes = RoutingOverDataSourceBeansWiringTest.App.class)
@TestPropertySource(properties = "spring.flyway.enabled=false")
@TenantIsolationTest(tenantColumn = "tenant_id", tables = "invoice")
class RoutingOverDataSourceBeansWiringTest {

    @JdbcApp
    static class App {

        @Bean
        DataSource tenantA() {
            return WiringDb.pool(TenantRouting.A);
        }

        @Bean
        DataSource tenantB() {
            return WiringDb.pool(TenantRouting.B);
        }

        @Bean
        @Primary
        DataSource dataSource(@Qualifier("tenantA") DataSource a, @Qualifier("tenantB") DataSource b) {
            TenantRouting routing = new TenantRouting();
            routing.setTargetDataSources(Map.of("A", a, "B", b));
            routing.setDefaultTargetDataSource(a);
            return routing;
        }

        @Bean
        TenantSeeder seeder(JdbcTemplate jdbc) {
            return TenantRouting.seeder(jdbc);
        }
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void targetBeansAreNotReportedAsUnobserved(ObserverSetup setup) {
        assertThat(setup.warnings()).isEmpty();
        assertThat(setup.selfChecked()).isTrue();
    }

    @Test
    void bRoutedToItsOwnDatabaseIsObservedOnce(TenantGuard guard) {
        guard.runAs("B", () -> TenantRouting.as("B",
                () -> jdbc.queryForObject("SELECT sum(amount) FROM invoice", BigDecimal.class)));

        assertThat(guard.findings()).isEmpty();
        assertThat(guard.rowReads()).isEqualTo(Map.of(new RowRead("invoice", "B"), 2L));
    }

    @Test
    void theFallbackLeakIsObservedOnce(TenantGuard guard) {
        guard.runAs("B", () -> jdbc.queryForObject("SELECT sum(amount) FROM invoice", BigDecimal.class));

        WiringDb.assertLeakOfA(guard);
    }
}
