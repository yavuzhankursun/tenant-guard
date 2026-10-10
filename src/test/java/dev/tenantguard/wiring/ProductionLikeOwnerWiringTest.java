package dev.tenantguard.wiring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tenantguard.TenantGuard;
import dev.tenantguard.TenantSeeder;
import dev.tenantguard.junit.ObserverSetup;
import dev.tenantguard.junit.TenantIsolationTest;
import dev.tenantguard.wiring.jpa.JpaApp;
import dev.tenantguard.wiring.jpa.WiringInvoice;
import jakarta.persistence.EntityManager;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The production-like case: the app logs in as an ordinary role (NOSUPERUSER, NOBYPASSRLS, no
 * CREATEROLE) that owns its database and therefore the tables Hibernate creates. {@code tg_app}
 * already exists in the cluster (as it would after a DBA created it) and was granted to the app role
 * once; the installer must use that membership instead of granting it again, which only a role with
 * ADMIN on {@code tg_app} may do.
 */
@SpringBootTest(classes = ProductionLikeOwnerWiringTest.App.class)
@TestPropertySource(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@TenantIsolationTest(tenantColumn = "tenant_id")
class ProductionLikeOwnerWiringTest {

    static final String ROLE = "wiring_owner";
    static final String PASSWORD = "owner";
    static final String URL;

    static {
        WiringDb.loginRole(ROLE, PASSWORD, "NOSUPERUSER NOBYPASSRLS NOCREATEROLE");
        WiringDb.observedRole();
        WiringDb.admin("postgres", "GRANT tg_app TO " + ROLE);
        URL = WiringDb.create("wiring_prod_owner", ROLE);
    }

    @JpaApp
    static class App {

        @Bean
        TenantSeeder seeder(JdbcTemplate jdbc) {
            return WiringDb.seeder(jdbc);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", () -> ROLE);
        registry.add("spring.datasource.password", () -> PASSWORD);
    }

    @Autowired
    EntityManager entityManager;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theAppRoleIsAnOrdinaryOwnerAndTheSelfCheckPasses(ObserverSetup setup, TenantGuard guard) {
        guard.deactivate();
        Map<String, Object> role = jdbc.queryForMap("""
                SELECT r.rolname, r.rolsuper, r.rolbypassrls, t.tableowner
                FROM pg_roles r JOIN pg_tables t ON t.tablename = 'invoice' AND t.schemaname = 'public'
                WHERE r.rolname = current_user""");
        guard.activate();

        assertThat(role).containsEntry("rolname", ROLE).containsEntry("rolsuper", false)
                .containsEntry("rolbypassrls", false).containsEntry("tableowner", ROLE);
        assertThat(setup.selfChecked()).isTrue();
        assertThat(setup.installedTables()).containsExactly("invoice");
    }

    @Test
    void aLeakyJpqlReadIsObserved(TenantGuard guard) {
        int rows = guard.callAs("B", () -> entityManager
                .createQuery("select i from WiringInvoice i", WiringInvoice.class).getResultList().size());

        assertThat(rows).isEqualTo(4);
        WiringDb.assertLeakOfA(guard);
    }
}
