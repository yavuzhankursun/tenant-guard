package dev.tenantguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tenantguard.Finding.Rule;
import dev.tenantguard.sample.InvoiceRepository;
import dev.tenantguard.sample.ReportService;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The eight prototype scenarios. Assertions encode the hypothesis; a red test is a result, not a bug.
 */
class ObserverScenariosTest extends GuardedTestBase {

    @Autowired
    InvoiceRepository invoices;

    @Autowired
    ReportService reports;

    @Test
    @DisplayName("1. Doğrudan tenant filtreli sorgu, seq scan: alarm yok")
    void directlyFilteredQueryRaisesNothing() {
        String sql = "SELECT id, number FROM invoice WHERE tenant_id = 'B'";
        report("plan", explain(sql));

        List<Map<String, Object>> rows = guard.callAs(TENANT_B, () -> jdbc.queryForList(sql));

        assertThat(rows).hasSize(2);
        assertThat(guard.findings()).as("findings").isEmpty();
        report("observed", guard.observations().toString());
    }

    @Test
    @DisplayName("2. Filtresiz aggregate: aktörlü ve pasif kural alarm verir")
    void unfilteredAggregateIsCaught() {
        guard.runAs(TENANT_B, () -> jdbc.queryForObject("SELECT sum(amount) FROM invoice", Long.class));

        assertThat(guard.findings(Rule.ACTOR_MISMATCH))
                .singleElement()
                .satisfies(f -> assertThat(f.tenants()).containsExactly(TENANT_A));
        assertThat(guard.findings(Rule.MULTI_TENANT_STATEMENT)).hasSize(1);
    }

    @Test
    @DisplayName("3. JOIN üzerinden filtrelenen alt tablo: yanlış alarm çıkıyor mu?")
    void joinFilteredChildTable() {
        String sql = "SELECT l.id, l.description FROM invoice_line l "
                + "JOIN invoice i ON i.id = l.invoice_id WHERE i.tenant_id = 'B'";
        report("plan", explain(sql));

        List<Map<String, Object>> rows = guard.callAs(TENANT_B, () -> jdbc.queryForList(sql));

        assertThat(rows).as("result is correctly filtered").hasSize(4);
        report("findings", guard.findings().toString());
        // Hypothesis: the executor reads A's lines before the join discards them -> SUSPECT on invoice_line only.
        assertThat(guard.findings(Rule.ACTOR_MISMATCH))
                .extracting(Finding::table)
                .containsExactly("invoice_line");
    }

    @Test
    @DisplayName("4a. readOnly transaction: notice kaybolmuyor")
    void readOnlyTransactionKeepsNotices() {
        ReportService.ReadOnlyTotal result = guard.callAs(TENANT_B, reports::leakyTotalReadOnly);

        assertThat(result.transactionReadOnly()).as("server-side transaction_read_only").isEqualTo("on");
        assertThat(guard.findings(Rule.ACTOR_MISMATCH)).isNotEmpty();
    }

    @Test
    @DisplayName("4b. Rollback olan transaction: notice kaybolmuyor")
    void rolledBackTransactionKeepsNotices() {
        assertThatThrownBy(() -> guard.runAs(TENANT_B, reports::leakyTotalThenFail))
                .isInstanceOf(IllegalStateException.class);

        assertThat(guard.findings(Rule.ACTOR_MISMATCH)).isNotEmpty();
    }

    @Test
    @DisplayName("5. Superuser bağlantı: self-check FAIL verir")
    void superuserConnectionFailsSelfCheck() {
        DataSource superuser = new GuardedDataSource(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()),
                guard, null);

        assertThatThrownBy(() -> SelfCheck.verify(superuser, guard))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bypasses row level security")
                .hasMessageContaining("superuser=true");
    }

    @Test
    @DisplayName("6. Mevcut RLS politikası olan tablo: kurulum açık hatayla durur")
    void existingPolicyIsRefused() throws Exception {
        try (Connection owner = ownerConnection()) {
            assertThatThrownBy(() -> ObserverInstaller.install(owner, "tenant_id", List.of("legacy_doc")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("legacy_isolation");
        }
    }

    @Test
    @DisplayName("7. Hibernate yolu: notice'ler yutulmuyor")
    void hibernatePathDoesNotSwallowNotices() {
        guard.runAs(TENANT_B, invoices::findAll);

        report("harvestedRows", guard.harvestedRows().toString());
        assertThat(guard.findings(Rule.ACTOR_MISMATCH))
                .singleElement()
                .satisfies(f -> assertThat(f.tenants()).containsExactly(TENANT_A));
    }

    @Test
    @DisplayName("8a. findById(A'nın ID'si), B aktörü: aktörlü mod yakalar")
    void findByIdIdorCaughtInActorMode() {
        guard.runAs(TENANT_B, () -> invoices.findById(invoiceOfA));

        assertThat(guard.findings(Rule.ACTOR_MISMATCH))
                .singleElement()
                .satisfies(f -> assertThat(f.tenants()).containsExactly(TENANT_A));
    }

    @Test
    @DisplayName("8b. findById(A'nın ID'si), aktör yok: pasif kural kaçırır")
    void findByIdIdorMissedInPassiveMode() {
        assertThat(invoices.findById(invoiceOfA)).isPresent();

        assertThat(guard.observations()).as("the row read was observed").isNotEmpty();
        assertThat(guard.findings()).as("but no passive rule fires").isEmpty();
    }

    @Test
    @DisplayName("Statement.getConnection() gözlenen bağlantıyı döndürür")
    void statementGetConnectionStaysObserved() throws Exception {
        guard.runAs(TENANT_B, () -> jdbc.execute((java.sql.Statement first) -> {
            try (java.sql.Statement second = first.getConnection().createStatement();
                 java.sql.ResultSet rs = second.executeQuery("SELECT count(*) FROM invoice")) {
                return rs.next();
            }
        }));

        assertThat(guard.findings(Rule.ACTOR_MISMATCH)).isNotEmpty();
    }

    @Test
    @DisplayName("Aynı PreparedStatement iki kez çalışınca ikinci çalıştırmanın notice'leri de toplanır")
    void reusedPreparedStatementIsObservedEveryTime() {
        guard.runAs(TENANT_B, () -> jdbc.execute("SELECT count(*) FROM invoice", (java.sql.PreparedStatement ps) -> {
            ps.executeQuery().close();
            ps.executeQuery().close();
            return null;
        }));

        assertThat(guard.findings(Rule.ACTOR_MISMATCH)).hasSize(2);
    }

    /** Experiment output: plans and harvest points are the answers we are after, not just green/red. */
    private static void report(String key, String value) {
        System.out.printf("[scenario] %s:%n%s%n", key, value);
    }

    /** EXPLAIN does not execute, so the policy function runs no notices; shows qual order under RLS. */
    private String explain(String sql) {
        return String.join("\n", jdbc.queryForList("EXPLAIN (COSTS OFF) " + sql, String.class));
    }
}
