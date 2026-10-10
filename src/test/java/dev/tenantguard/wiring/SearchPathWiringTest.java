package dev.tenantguard.wiring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tenantguard.TenantGuard;
import dev.tenantguard.TenantSeeder;
import dev.tenantguard.junit.ObserverSetup;
import dev.tenantguard.junit.TenantIsolationTest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Disabled;
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
 * schema {@code app}. Discovery follows {@code current_schema()} and finds them, but the installer
 * grants {@code tg_app} only schema {@code public}: without USAGE on {@code app}, the observed role's
 * effective search_path is {@code public} alone, and the self-check's probe (created in {@code app})
 * does not resolve. Today the setup fails with:
 *
 * <pre>
 * TenantGuard self-check could not run: ERROR: relation "tg_probe" does not exist
 *   Position: 22.
 * It needs the observer installed (role tg_app, table tg_probe) and a DataSource user that may SET ROLE to the configured role.
 * </pre>
 *
 * Precise enough not to run blind ({@code WiringSetupFailureTest} holds that invariant for either
 * outcome); this test is the expectation once the installer supports non-public schemas.
 */
@Disabled("needs schema support, see Faz 3c: today setup fails with 'TenantGuard self-check could not run: "
        + "ERROR: relation \"tg_probe\" does not exist' because tg_app is granted schema public only")
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
