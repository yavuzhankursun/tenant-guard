package dev.tenantguard.wiring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tenantguard.Finding;
import dev.tenantguard.RowRead;
import dev.tenantguard.TenantGuard;
import dev.tenantguard.TenantSeeder;
import dev.tenantguard.junit.TenantIsolationTest;
import dev.tenantguard.wiring.jpa.JpaApp;
import dev.tenantguard.wiring.jpa.WiringInvoice;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Spring transactions over JPA: {@code JpaTransactionManager} exposes Hibernate's connection to
 * {@code JdbcTemplate}, so both run on one guarded connection; {@code PROPAGATION_REQUIRES_NEW}
 * suspends it and borrows a second one while the guard is active. Every read is observed.
 */
@SpringBootTest(classes = TransactionWiringTest.App.class)
@TenantIsolationTest(tenantColumn = "tenant_id", tables = "invoice")
class TransactionWiringTest {

    static final String URL = WiringDb.create("wiring_transactions");

    @JpaApp
    static class App {

        @Bean
        Reports reports(EntityManager entityManager, JdbcTemplate jdbc, PlatformTransactionManager transactions) {
            return new Reports(entityManager, jdbc, transactions);
        }

        @Bean
        TenantSeeder seeder(JdbcTemplate jdbc) {
            return WiringDb.seeder(jdbc);
        }
    }

    /** Backend pids show which statements shared a connection. */
    static class Reports {

        private final EntityManager entityManager;
        private final JdbcTemplate jdbc;
        private final TransactionTemplate requiresNew;

        Reports(EntityManager entityManager, JdbcTemplate jdbc, PlatformTransactionManager transactions) {
            this.entityManager = entityManager;
            this.jdbc = jdbc;
            this.requiresNew = new TransactionTemplate(transactions);
            this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }

        @Transactional
        public List<Integer> leakyJpaThenLeakyJdbc() {
            int jpaPid = ((Number) entityManager.createNativeQuery("SELECT pg_backend_pid()").getSingleResult()).intValue();
            entityManager.createQuery("select i from WiringInvoice i", WiringInvoice.class).getResultList();
            jdbc.queryForObject("SELECT sum(amount) FROM invoice", BigDecimal.class);
            return List.of(jpaPid, pid());
        }

        @Transactional
        public List<Integer> leakyReadInARequiresNewTransaction() {
            int outerPid = pid();
            int innerPid = requiresNew.execute(status -> {
                jdbc.queryForObject("SELECT max(amount) FROM invoice", BigDecimal.class);
                return pid();
            });
            return List.of(outerPid, innerPid);
        }

        private int pid() {
            return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", () -> WiringDb.ADMIN);
        registry.add("spring.datasource.password", () -> WiringDb.ADMIN_PASSWORD);
    }

    @Autowired
    Reports reports;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void jpaAndJdbcShareTheTransactionsConnectionAndBothReadsAreObserved(TenantGuard guard) {
        assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);

        List<Integer> pids = guard.callAs("B", reports::leakyJpaThenLeakyJdbc);

        assertThat(pids.get(0)).as("JdbcTemplate joined Hibernate's connection").isEqualTo(pids.get(1));
        assertThat(guard.findings(Finding.Rule.ACTOR_MISMATCH))
                .extracting(Finding::sql)
                .anySatisfy(sql -> assertThat(sql).as("Hibernate's JPQL").startsWith("select ").contains(" from invoice "))
                .anySatisfy(sql -> assertThat(sql).isEqualTo("SELECT sum(amount) FROM invoice"));
        WiringDb.assertLeakOfA(guard, 4);
    }

    @Test
    void aReadInARequiresNewTransactionUsesAnotherConnectionAndIsObserved(TenantGuard guard) {
        List<Integer> pids = guard.callAs("B", reports::leakyReadInARequiresNewTransaction);

        assertThat(pids.get(0)).as("REQUIRES_NEW borrowed a second connection").isNotEqualTo(pids.get(1));
        assertThat(guard.rowReads()).containsEntry(new RowRead("invoice", "A"), 2L);
        WiringDb.assertLeakOfA(guard);
    }
}
