package dev.tenantguard.wiring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tenantguard.TenantGuard;
import dev.tenantguard.TenantSeeder;
import dev.tenantguard.junit.ObserverSetup;
import dev.tenantguard.junit.TenantIsolationTest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Hikari's {@code connection-init-sql} sets {@code search_path TO app, public}; the tables live in
 * schema {@code app}. Discovery follows the search_path and the installer grants {@code tg_app}
 * every schema on it, so the probe (created in {@code app}) resolves for the observed role. Before
 * schema support the setup failed with "relation tg_probe does not exist";
 * {@code WiringSetupFailureTest} still holds the invariant that this wiring never runs blind.
 */
@SpringBootTest(classes = SearchPathWiringTest.App.class)
@TestPropertySource(properties = {"spring.flyway.enabled=false",
        "spring.datasource.hikari.connection-init-sql=SET search_path TO app, public"})
@TenantIsolationTest(tenantColumn = "tenant_id")
class SearchPathWiringTest {

    static final String URL = WiringDb.create("wiring_search_path_expected");

    static {
        WiringDb.admin("wiring_search_path_expected", "CREATE SCHEMA app", "SET search_path TO app", WiringDb.INVOICE_DDL);
    }

    @JdbcApp
    static class App {

        @Bean
        TenantSeeder seeder(JdbcTemplate jdbc) {
            return WiringDb.seeder(jdbc);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", () -> WiringDb.ADMIN);
        registry.add("spring.datasource.password", () -> WiringDb.ADMIN_PASSWORD);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void tablesInTheAppSchemaAreDiscoveredAndSelfChecked(ObserverSetup setup) {
        assertThat(setup.installedTables()).containsExactly("invoice");
        assertThat(setup.selfChecked()).isTrue();
    }

    @Test
    void aLeakyReadThroughTheSearchPathIsObserved(TenantGuard guard) {
        guard.runAs("B", () -> jdbc.queryForObject("SELECT sum(amount) FROM invoice", BigDecimal.class));

        WiringDb.assertLeakOfA(guard);
    }
}
