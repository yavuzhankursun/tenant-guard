package dev.tenantguard;

import dev.tenantguard.junit.TenantIsolationTest;
import dev.tenantguard.sample.SampleApp;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Scenario fixture. {@link TenantIsolationTest} installs the observer, self-checks and arms the
 * guard; this class only owns the data. Tenants: A owns invoices A-1/A-2 with {@link Extremes}
 * amounts, B owns B-1/B-2 (100/200), each invoice with two lines.
 *
 * <p>Ordering: the extension's {@code beforeEach} runs before {@link #seedInitialTenants()}, so the
 * guard is already armed when we seed. Seeding therefore deactivates the guard (TRUNCATE and INSERT
 * run as the owner, bypassing the observer and tg_app's missing TRUNCATE grant), then re-arms it.
 */
@SpringBootTest(classes = SampleApp.class)
@TenantIsolationTest(tenantColumn = "tenant_id", tables = {"invoice", "invoice_line"})
public abstract class GuardedTestBase {

    static final String TENANT_A = "A";
    static final String TENANT_B = "B";

    /** Testcontainers' default user is a superuser: exactly the setup that would make RLS silently inert. */
    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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

    /** Tenants present before the test body runs; the differential scenario starts with B only. */
    List<String> initialTenants() {
        return List.of(TENANT_A, TENANT_B);
    }

    @BeforeEach
    void seedInitialTenants() {
        guard.deactivate();
        jdbc.execute("TRUNCATE invoice_line, invoice RESTART IDENTITY");
        initialTenants().forEach(tenant -> seedTenant(tenant, 1));
        guard.activate();
        guard.reset();
    }

    public static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    void seedTenantAsOwner(String tenant) {
        seedTenantAsOwner(tenant, 1);
    }

    /** Seeds as the owner (bypassing the observer), then restores the guard state. A ignores the scale. */
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
            BigDecimal amount = TENANT_A.equals(tenant)
                    ? Extremes.amounts().get(i - 1)
                    : BigDecimal.valueOf(100L * i * amountScale);
            Long id = jdbc.queryForObject(
                    "INSERT INTO invoice (tenant_id, number, amount) VALUES (?, ?, ?) RETURNING id",
                    Long.class, tenant, tenant + "-" + i, amount);
            jdbc.update("INSERT INTO invoice_line (tenant_id, invoice_id, description) VALUES (?, ?, ?), (?, ?, ?)",
                    tenant, id, "line 1", tenant, id, "line 2");
        }
        if (TENANT_A.equals(tenant)) {
            invoiceOfA = jdbc.queryForObject(
                    "SELECT min(id) FROM invoice WHERE tenant_id = ?", Long.class, TENANT_A);
        }
    }
}
