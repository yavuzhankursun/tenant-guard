package dev.tenantguard.scan;

import dev.tenantguard.ObserverInstaller;
import dev.tenantguard.SelfCheck;
import dev.tenantguard.TenantGuard;
import dev.tenantguard.sample.SampleApp;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Mirrors {@code GuardedTestBase} (package-private, so not extendable from here) with no tenants
 * seeded: the scanner owns seeding. Empty tables, observer installed, self-check passed, guard active.
 */
@SpringBootTest(classes = SampleApp.class)
@AutoConfigureMockMvc
@Import(ScanGuardConfig.class)
abstract class ScanTestBase {

    static final String TENANT_A = "A";
    static final String TENANT_B = "B";

    // Superuser by default, like the base it mirrors; the guard switches to the observed role.
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

    @BeforeEach
    void armGuard() throws SQLException {
        guard.deactivate();
        jdbc.execute("TRUNCATE invoice_line, invoice RESTART IDENTITY");
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
}
