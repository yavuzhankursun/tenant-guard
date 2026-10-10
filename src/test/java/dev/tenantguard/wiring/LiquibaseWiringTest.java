package dev.tenantguard.wiring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tenantguard.TenantGuard;
import dev.tenantguard.TenantSeeder;
import dev.tenantguard.junit.ObserverSetup;
import dev.tenantguard.junit.TenantIsolationTest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Liquibase instead of Flyway. It migrates through the guarded DataSource while the guard is
 * inactive, so the app user owns the tables exactly as with Flyway. Liquibase's own tables carry
 * no tenant column, so auto-discovery leaves them alone.
 */
@SpringBootTest(classes = LiquibaseWiringTest.App.class)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.liquibase.enabled=true",
        "spring.liquibase.change-log=classpath:wiring/liquibase-changelog.yaml"})
@TenantIsolationTest(tenantColumn = "tenant_id")
class LiquibaseWiringTest {

    static final String URL = WiringDb.create("wiring_liquibase");

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
    void liquibaseCreatedTheTablesAsTheAppUserAndDiscoverySkipsItsOwnTables(ObserverSetup setup, TenantGuard guard) {
        guard.deactivate();
        List<Boolean> ownedByAppUser = jdbc.queryForList("""
                SELECT DISTINCT tableowner = current_user FROM pg_tables
                WHERE schemaname = 'public' AND tablename IN ('invoice', 'invoice_line')""", Boolean.class);
        List<String> changelog = jdbc.queryForList(
                "SELECT id FROM databasechangelog ORDER BY orderexecuted", String.class);
        guard.activate();

        assertThat(ownedByAppUser).containsExactly(true);
        assertThat(changelog).containsExactly("1-invoice");
        assertThat(setup.installedTables()).containsExactly("invoice", "invoice_line");
        assertThat(setup.skippedTables()).isEmpty();
    }

    @Test
    void aLeakyReadIsObserved(TenantGuard guard) {
        guard.runAs("B", () -> jdbc.queryForObject("SELECT sum(amount) FROM invoice", BigDecimal.class));

        WiringDb.assertLeakOfA(guard);
    }
}
