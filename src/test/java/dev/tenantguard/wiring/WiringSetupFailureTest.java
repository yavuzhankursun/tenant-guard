package dev.tenantguard.wiring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tenantguard.TenantGuard;
import dev.tenantguard.TenantSeeder;
import dev.tenantguard.junit.TenantIsolationTest;
import dev.tenantguard.wiring.jpa.JpaApp;
import dev.tenantguard.wiring.schema.SchemaPerTenantApp;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.Event;
import org.junit.platform.testkit.engine.Events;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Wirings the extension cannot observe must fail before the test body runs, with a message that
 * names the cause; they must never run and report nothing. Each fixture is a static nested test
 * class (Surefire never selects those by itself) run through the JUnit testkit. A fixture's body
 * throws, so a failure carrying the setup message proves the body never ran.
 */
class WiringSetupFailureTest {

    static final String BODY_RAN = "the fixture's test body ran";

    // --- 6: ddl-auto under a role that only has DML grants ---------------------------------------

    static final String DML_ROLE = "wiring_dml";
    static final String DML_PASSWORD = "dml";

    @SpringBootTest(classes = NonOwnerFixture.App.class)
    @TestPropertySource(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
    @TenantIsolationTest(tenantColumn = "tenant_id")
    static class NonOwnerFixture {

        static final String URL = WiringDb.create("wiring_ddl_dml");

        /**
         * The schema exists, created by someone else; the app role may read and write, nothing more.
         * It is even a member of tg_app, so the failure below is about owning the tables, not the role.
         */
        static {
            WiringDb.loginRole(DML_ROLE, DML_PASSWORD, "NOSUPERUSER NOBYPASSRLS NOCREATEROLE");
            WiringDb.observedRole();
            WiringDb.admin("postgres", "GRANT tg_app TO " + DML_ROLE);
            WiringDb.admin("wiring_ddl_dml", WiringDb.INVOICE_DDL,
                    "GRANT SELECT, INSERT, UPDATE, DELETE ON invoice TO " + DML_ROLE,
                    "GRANT USAGE ON SEQUENCE invoice_id_seq TO " + DML_ROLE);
        }

        @JpaApp
        static class App {
        }

        @DynamicPropertySource
        static void datasource(DynamicPropertyRegistry registry) {
            registry.add("spring.datasource.url", () -> URL);
            registry.add("spring.datasource.username", () -> DML_ROLE);
            registry.add("spring.datasource.password", () -> DML_PASSWORD);
        }

        @Test
        void body() {
            throw new AssertionError(BODY_RAN);
        }
    }

    @Test
    @DisplayName("ddl-auto, uygulama rolü sahip değil (yalnız DML): kurulum 'must own the tenant tables' ile düşer")
    void nonOwnerAppRoleFailsTheInstallAndSaysItMustOwnTheTables() {
        assertThat(setupFailure(NonOwnerFixture.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("TenantGuard could not install the observer as user '" + DML_ROLE + "' (SQLState 42501")
                .hasMessageContaining("must own the tenant tables")
                .hasMessageContaining("not ddl-auto under another role");
    }

    // --- 8: schema per tenant, no tenant column -------------------------------------------------

    @SpringBootTest(classes = SchemaPerTenantApp.class)
    @TestPropertySource(properties = "spring.flyway.enabled=false")
    @TenantIsolationTest(tenantColumn = "tenant_id")
    static class SchemaPerTenantFixture {

        static final String URL = WiringDb.create("wiring_schema_per_tenant");

        static {
            WiringDb.admin("wiring_schema_per_tenant",
                    "CREATE SCHEMA tenant_a", "CREATE SCHEMA tenant_b",
                    "CREATE TABLE tenant_a.invoice (id bigserial PRIMARY KEY, number text NOT NULL, amount numeric NOT NULL)",
                    "CREATE TABLE tenant_b.invoice (id bigserial PRIMARY KEY, number text NOT NULL, amount numeric NOT NULL)");
        }

        @DynamicPropertySource
        static void datasource(DynamicPropertyRegistry registry) {
            registry.add("spring.datasource.url", () -> URL);
            registry.add("spring.datasource.username", () -> WiringDb.ADMIN);
            registry.add("spring.datasource.password", () -> WiringDb.ADMIN_PASSWORD);
        }

        @Test
        void body() {
            throw new AssertionError(BODY_RAN);
        }
    }

    @Test
    @DisplayName("Schema-per-tenant (Hibernate SCHEMA multitenancy, tenant_id yok): kör olduğunu ve kapsam dışı olduğunu söyler")
    void schemaPerTenantFailsAsBlindAndSaysTheDesignIsOutOfScope() {
        assertThat(setupFailure(SchemaPerTenantFixture.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("TenantGuard is blind: no observable table in the current schema has column 'tenant_id'")
                .hasMessageContaining("schema-per-tenant")
                .hasMessageContaining("out of scope");
    }

    // --- 3: a DataSource name that does not exist ------------------------------------------------

    @SpringBootTest(classes = TwoDataSourcesApp.class)
    @TenantIsolationTest(tenantColumn = "tenant_id", tables = "invoice", dataSources = {"dataSource", "replica"})
    static class UnknownDataSourceFixture {

        @Test
        void body() {
            throw new AssertionError(BODY_RAN);
        }
    }

    @Test
    @DisplayName("dataSources içinde olmayan bean adı: mevcut DataSource bean'lerini listeler")
    void anUnknownDataSourceNameFailsAndListsTheDataSourceBeans() {
        assertThat(setupFailure(UnknownDataSourceFixture.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("TenantGuard found no DataSource bean named 'replica' (from @TenantIsolationTest(dataSources)); "
                        + "DataSource beans: [dataSource, reporting]");
    }

    // --- 7: search_path set by the pool, tables outside public ------------------------------------

    @SpringBootTest(classes = SearchPathFixture.App.class)
    @TestPropertySource(properties = {"spring.flyway.enabled=false",
            "spring.datasource.hikari.connection-init-sql=SET search_path TO app, public"})
    @TenantIsolationTest(tenantColumn = "tenant_id")
    static class SearchPathFixture {

        static final String URL = WiringDb.create("wiring_search_path");

        static {
            WiringDb.admin("wiring_search_path", "CREATE SCHEMA app", "SET search_path TO app", WiringDb.INVOICE_DDL);
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

        /** Runs only if setup passed: then the leak must be seen, or the extension ran blind. */
        @Test
        void leakyReadIsObserved(TenantGuard guard) {
            guard.runAs("B", () -> jdbc.queryForObject("SELECT sum(amount) FROM invoice", BigDecimal.class));
            WiringDb.assertLeakOfA(guard);
        }
    }

    /**
     * Whatever schema support exists, this wiring may not run blind: either the leak is observed, or
     * setup fails with a TenantGuard message before the body runs. {@link SearchPathWiringTest} holds
     * the "observed" expectation, disabled until the installer handles non-public schemas.
     */
    @Test
    @DisplayName("search_path=app,public: ya gözlenir ya da TenantGuard mesajıyla kurulumda düşer, asla kör koşmaz")
    void searchPathOutsidePublicIsObservedOrFailsPreciselyButNeverRunsBlind() {
        Events events = WiringDb.runFixture(SearchPathFixture.class);
        if (events.failed().count() == 0) {
            events.assertStatistics(stats -> stats.started(1).succeeded(1));
            return;
        }
        Throwable failure = singleFailure(events);
        System.out.printf("[wiring] search_path fixture failed in setup: %s%n", failure.getMessage());
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageStartingWith("TenantGuard");
    }

    static Throwable setupFailure(Class<?> fixture) {
        Throwable failure = singleFailure(WiringDb.runFixture(fixture));
        assertThat(failure).as("setup failed before the body").hasMessageNotContaining(BODY_RAN);
        return failure;
    }

    private static Throwable singleFailure(Events events) {
        assertThat(events.failed().count()).as("exactly one failed test in the fixture").isEqualTo(1);
        Event failed = events.failed().list().getFirst();
        return failed.getPayload(TestExecutionResult.class).flatMap(TestExecutionResult::getThrowable).orElseThrow();
    }
}
