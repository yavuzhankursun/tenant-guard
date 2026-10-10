package dev.tenantguard;

import dev.tenantguard.sample.SampleApp;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Lifecycle the JUnit extension will own: seed as owner, install observer, self-check, then hand
 * over an active guard. Tenants: A owns invoices A-1/A-2, B owns B-1/B-2, each with two lines.
 */
@SpringBootTest(classes = SampleApp.class)
@Import(GuardTestConfig.class)
abstract class GuardedTestBase {

    static final String TENANT_A = "A";
    static final String TENANT_B = "B";

    // Testcontainers' default user is a superuser: exactly the setup that would make RLS silently inert.
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    TenantGuard guard;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    Long invoiceOfA;

    /** Tenants present before the guard is armed; the differential scenario starts with B only. */
    List<String> initialTenants() {
        return List.of(TENANT_A, TENANT_B);
    }

    @BeforeEach
    void armGuard() throws SQLException {
        guard.deactivate();
        jdbc.execute("TRUNCATE invoice_line, invoice RESTART IDENTITY");
        initialTenants().forEach(tenant -> seedTenant(tenant, 1));
        try (Connection owner = ownerConnection()) {
            ObserverInstaller.install(owner, "tenant_id", List.of("invoice", "invoice_line"));
        }
        guard.activate();
        SelfCheck.verify(dataSource, guard);
        guard.reset();
    }

    @AfterEach
    void disarmGuard() {
        guard.deactivate();
        guard.reset();
    }

    static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** Seeds as the owner (bypassing the observer), then restores the guard state. */
    void seedTenantAsOwner(String tenant, int amountScale) {
        boolean wasActive = guard.isActive();
        guard.deactivate();
        try {
            seedTenant(tenant, amountScale);
        } finally {
            if (wasActive) {
                guard.activate();
            }
        }
    }

    private void seedTenant(String tenant, int amountScale) {
        for (int i = 1; i <= 2; i++) {
            Long id = jdbc.queryForObject(
                    "INSERT INTO invoice (tenant_id, number, amount) VALUES (?, ?, ?) RETURNING id",
                    Long.class, tenant, tenant + "-" + i, 100 * i * amountScale);
            jdbc.update("INSERT INTO invoice_line (tenant_id, invoice_id, description) VALUES (?, ?, ?), (?, ?, ?)",
                    tenant, id, "line 1", tenant, id, "line 2");
        }
        if (TENANT_A.equals(tenant)) {
            invoiceOfA = jdbc.queryForObject(
                    "SELECT min(id) FROM invoice WHERE tenant_id = ?", Long.class, TENANT_A);
        }
    }
}
