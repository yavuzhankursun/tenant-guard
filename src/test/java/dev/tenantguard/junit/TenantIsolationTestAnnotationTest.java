package dev.tenantguard.junit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tenantguard.Finding.Rule;
import dev.tenantguard.GuardedDataSource;
import dev.tenantguard.GuardedTestBase;
import dev.tenantguard.ObserverInstaller;
import dev.tenantguard.TenantGuard;
import dev.tenantguard.TenantSeeder;
import dev.tenantguard.sample.InvoiceRepository;
import dev.tenantguard.sample.SampleApp;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The user-facing setup: only the annotation and a seeder. No explicit {@code @Import}: the guard bean
 * existing at all proves Spring Boot honours {@code @Import} as a meta-annotation on the test class.
 * The seeder is listed in {@code classes}: with explicit classes Spring Boot ignores nested
 * {@code @TestConfiguration}s.
 */
@SpringBootTest(classes = {SampleApp.class, TenantIsolationTestAnnotationTest.Seeding.class})
@TenantIsolationTest(tenantColumn = "tenant_id")
class TenantIsolationTestAnnotationTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", GuardedTestBase.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", GuardedTestBase.POSTGRES::getUsername);
        registry.add("spring.datasource.password", GuardedTestBase.POSTGRES::getPassword);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Seeding {

        @Bean
        TenantSeeder tenantSeeder(JdbcTemplate jdbc) {
            return tenant -> {
                if ("A".equals(tenant)) {
                    // The extension seeds the victim first: start every test from a clean slate.
                    jdbc.execute("TRUNCATE invoice_line, invoice RESTART IDENTITY");
                }
                jdbc.update("INSERT INTO invoice (tenant_id, number, amount) VALUES (?, ?, 100)", tenant, tenant + "-1");
            };
        }
    }

    @Autowired
    InvoiceRepository invoices;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void guardParameterIsResolvedAndActive(TenantGuard guard, TenantSeeder seeder) throws Exception {
        assertThat(guard.isActive()).isTrue();
        assertThat(seeder).isNotNull();
        assertThat(dataSource.isWrapperFor(GuardedDataSource.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoice", Long.class))
                .as("the extension seeded victim and attacker").isEqualTo(2);
    }

    @Test
    void autoDiscoveryObservesEveryTenantTableIncludingOnesWithTheirOwnPolicy(ObserverSetup setup) {
        assertThat(setup.tenantColumn()).isEqualTo("tenant_id");
        assertThat(setup.installedTables()).contains("invoice", "invoice_line", "legacy_doc")
                .doesNotContain(ObserverInstaller.PROBE_TABLE, "flyway_schema_history");
        assertThat(setup.skippedTables()).isEmpty();
    }

    @Test
    void leakyFindAllAsAttackerIsCaught(TenantGuard guard) {
        guard.runAs("B", invoices::findAll);

        assertThat(guard.findings(Rule.ACTOR_MISMATCH))
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.table()).isEqualTo("invoice");
                    assertThat(f.tenants()).containsExactly("A");
                });
    }

    @Test
    void selfCheckRanAndLeftNoObservationsBehind(TenantGuard guard, ObserverSetup setup) {
        assertThat(setup.selfChecked()).isTrue();
        assertThat(guard.observations()).as("reset after the self-check").isEmpty();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + ObserverInstaller.PROBE_TABLE, Long.class))
                .isEqualTo(1);
        assertThat(guard.observations()).as("the probe row is observed as tg_app")
                .anySatisfy(o -> assertThat(o.tenantsByTable()).containsKey(ObserverInstaller.PROBE_TABLE));
        assertThat(guard.findings()).as("one tenant, no actor: nothing to report").isEmpty();
    }
}
