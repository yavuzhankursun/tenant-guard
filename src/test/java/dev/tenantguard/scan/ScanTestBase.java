package dev.tenantguard.scan;

import dev.tenantguard.GuardedTestBase;
import dev.tenantguard.TenantGuard;
import dev.tenantguard.junit.TenantIsolationTest;
import dev.tenantguard.sample.SampleApp;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Scanner tests start with empty tables: the scanner owns seeding. The extension installs the
 * observer and arms the guard first; truncating then runs with the guard inactive, as the owner.
 * No {@code TenantSeeder} bean is registered on purpose, or the extension would seed both tenants
 * before the scan's baseline. Shares the scenario tests' container instead of starting a second one.
 */
@SpringBootTest(classes = SampleApp.class)
@AutoConfigureMockMvc
@TenantIsolationTest(tenantColumn = "tenant_id", tables = {"invoice", "invoice_line"})
abstract class ScanTestBase {

    static final String TENANT_A = "A";
    static final String TENANT_B = "B";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", GuardedTestBase.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", GuardedTestBase.POSTGRES::getUsername);
        registry.add("spring.datasource.password", GuardedTestBase.POSTGRES::getPassword);
    }

    @Autowired
    TenantGuard guard;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void emptyTables() {
        guard.deactivate();
        jdbc.execute("TRUNCATE invoice_line, invoice RESTART IDENTITY");
        guard.activate();
        guard.reset();
    }
}
