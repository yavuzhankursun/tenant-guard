package example.partition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tenantguard.Finding;
import dev.tenantguard.Finding.Rule;
import dev.tenantguard.TenantGuard;
import dev.tenantguard.TenantSeeder;
import dev.tenantguard.junit.ObserverSetup;
import dev.tenantguard.junit.TenantIsolationTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Spring Data's own Hibernate {@code @TenantId} example, unchanged, on Postgres: which of its reads
 * does the tenant filter really cover? The sample's tests already hint at the answer (a disabled
 * {@code findById} test, an expected exception on native SQL); the observer shows the reads.
 */
@SpringBootTest(classes = {Application.class, TenantGuardFieldTest.Seeding.class})
@TenantIsolationTest(tenantColumn = "tenant", victim = "PIVOTAL", attacker = "VMWARE")
class TenantGuardFieldTest {

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

    @TestConfiguration(proxyBeanMethods = false)
    static class Seeding {
        @Bean
        TenantSeeder seeder(JdbcTemplate jdbc) {
            return tenant -> {
                if (tenant.equals("PIVOTAL")) {
                    jdbc.execute("TRUNCATE person");
                }
                jdbc.update("INSERT INTO person (id, tenant, name) VALUES (nextval('person_seq'), ?, ?)", tenant, "Adam");
                jdbc.update("INSERT INTO person (id, tenant, name) VALUES (nextval('person_seq'), ?, ?)", tenant, "Eve");
            };
        }
    }

    @Autowired Persons persons;
    @Autowired TenantIdentifierResolver currentTenant;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void actAsAttacker() {
        currentTenant.setCurrentTenant("VMWARE");
    }

    @Test
    @DisplayName("setup: both tenants seeded, observer on person")
    void setup(ObserverSetup setup) {
        assertThat(setup.installedTables()).containsExactly("person");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM person", Integer.class)).isEqualTo(4);
    }

    @Test
    @DisplayName("findAll(): @TenantId filter adds tenant = ?, PIVOTAL's rows never reach the policy")
    void findAllIsFiltered(TenantGuard guard) {
        List<Person> all = guard.callAs("VMWARE", () -> tx.execute(s -> persons.findAll()));

        assertThat(all).extracting(Person::getTenant).containsOnly("VMWARE");
        assertThat(guard.findings()).isEmpty();
    }

    @Test
    @DisplayName("JPQL by name: filter applied, CLEAN")
    void jpqlIsFiltered(TenantGuard guard) {
        Person eve = guard.callAs("VMWARE", () -> tx.execute(s -> persons.findJpqlByName("Eve")));

        assertThat(eve.getTenant()).isEqualTo("VMWARE");
        assertThat(guard.findings()).isEmpty();
    }

    @Test
    @DisplayName("findById(PIVOTAL's id) as VMWARE: what Hibernate 6.6 does, and what the executor read")
    void findByIdOfAnotherTenant(TenantGuard guard) {
        long pivotalId = jdbc.queryForObject("SELECT min(id) FROM person WHERE tenant = 'PIVOTAL'", Long.class);
        guard.reset();   // the lookup above legitimately read PIVOTAL's rows

        var found = guard.callAs("VMWARE", () -> tx.execute(s -> persons.findById(pivotalId)));

        // Hibernate 6.6 adds "and tenant = ?" to the load: the row is rejected by a leakproof predicate
        // before the policy, so the executor never reads it. The sample's disabled test is stale.
        assertThat(found).isEmpty();
        assertThat(guard.rowReads()).isEmpty();
        assertThat(guard.findings()).isEmpty();
    }

    @Test
    @DisplayName("native SQL by name: no filter; the sample expects a wrong-size exception, the observer reports the read")
    void nativeSqlIsUnfiltered(TenantGuard guard) {
        assertThatThrownBy(() -> guard.callAs("VMWARE", () -> tx.execute(s -> persons.findSqlByName("Adam"))))
                .isInstanceOf(IncorrectResultSizeDataAccessException.class);

        assertThat(guard.findings(Rule.ACTOR_MISMATCH)).singleElement()
                .satisfies(f -> assertThat(f.table()).isEqualTo("person"));
        assertThat(guard.findings(Rule.MULTI_TENANT_STATEMENT)).hasSize(1);
        assertThat(guard.findings()).extracting(Finding::rule).containsExactlyInAnyOrder(Rule.ACTOR_MISMATCH, Rule.MULTI_TENANT_STATEMENT);
    }
}
