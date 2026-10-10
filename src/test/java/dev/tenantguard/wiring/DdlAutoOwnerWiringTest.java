package dev.tenantguard.wiring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tenantguard.TenantGuard;
import dev.tenantguard.TenantSeeder;
import dev.tenantguard.junit.ObserverSetup;
import dev.tenantguard.junit.TenantIsolationTest;
import dev.tenantguard.wiring.jpa.JpaApp;
import dev.tenantguard.wiring.jpa.WiringInvoice;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * No migrations: Hibernate's {@code ddl-auto=create-drop} creates the schema, under the app's own
 * user. That user owns the tables, so the owner assumption holds as with Flyway.
 */
@SpringBootTest(classes = DdlAutoOwnerWiringTest.App.class)
@TestPropertySource(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@TenantIsolationTest(tenantColumn = "tenant_id")
class DdlAutoOwnerWiringTest {

    static final String URL = WiringDb.create("wiring_ddl_owner");

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
        registry.add("spring.datasource.username", () -> WiringDb.ADMIN);
        registry.add("spring.datasource.password", () -> WiringDb.ADMIN_PASSWORD);
    }

    @Autowired
    EntityManager entityManager;

    @Test
    void hibernateCreatedTableIsDiscoveredAndObserved(ObserverSetup setup) {
        assertThat(setup.installedTables()).containsExactly("invoice");
        assertThat(setup.selfChecked()).isTrue();
    }

    @Test
    void aLeakyJpqlReadIsObserved(TenantGuard guard) {
        int rows = guard.callAs("B", () -> entityManager
                .createQuery("select i from WiringInvoice i", WiringInvoice.class).getResultList().size());

        assertThat(rows).isEqualTo(4);
        WiringDb.assertLeakOfA(guard);
    }
}
