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
    @DisplayName("6a. Mevcut permissive RLS politikası olan tablo: observer RESTRICTIVE kurulur, izolasyon bozulmaz")
    void existingPolicyGetsRestrictiveObserverAndStaysIsolated() throws Exception {
        try (Connection owner = ownerConnection(); java.sql.Statement sql = owner.createStatement()) {
            sql.execute("TRUNCATE legacy_doc");
            sql.execute("INSERT INTO legacy_doc (tenant_id, title) VALUES ('A', 'a-doc'), ('B', 'b-doc')");
            assertThat(ObserverInstaller.policyKind(owner, "legacy_doc")).isEqualTo(ObserverInstaller.PolicyKind.RESTRICTIVE);
            guard.trust(ObserverInstaller.install(owner, "tenant_id", List.of("legacy_doc")));
        }
        guard.reset();

        List<String> asB = guard.callAs(TENANT_B, () -> jdbc.execute((Connection c) -> {
            try (java.sql.Statement st = c.createStatement()) {
                st.execute("SET app.tenant = 'B'");
                try (java.sql.ResultSet rs = st.executeQuery("SELECT title FROM legacy_doc ORDER BY title")) {
                    List<String> titles = new java.util.ArrayList<>();
                    while (rs.next()) {
                        titles.add(rs.getString(1));
                    }
                    return titles;
                } finally {
                    st.execute("RESET app.tenant");
                }
            }
        }));

        assertThat(asB).as("the app's own policy still filters").containsExactly("b-doc");
        assertThat(guard.observations()).as("the observer still sees the rows that pass it").isNotEmpty();
        assertThat(guard.findings(Rule.ACTOR_MISMATCH)).as("A's row was rejected before the observer").isEmpty();
    }

    @Test
    @DisplayName("6b. Yalnızca restrictive politikası olan tablo: observer hiçbir şey göremez, kurulum reddedilir")
    void tableWithoutAnyPermissivePolicyIsRefused() throws Exception {
        try (Connection owner = ownerConnection(); java.sql.Statement sql = owner.createStatement()) {
            sql.execute("CREATE TABLE tg_locked (tenant_id text NOT NULL)");
            sql.execute("ALTER TABLE tg_locked ENABLE ROW LEVEL SECURITY");
            sql.execute("CREATE POLICY lock ON tg_locked AS RESTRICTIVE USING (true)");
            try {
                assertThatThrownBy(() -> ObserverInstaller.install(owner, "tenant_id", List.of("tg_locked")))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("none is permissive for role 'tg_app'");
            } finally {
                sql.execute("DROP TABLE tg_locked");
            }
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

    @Test
    @DisplayName("fetchSize=1 cursor: ilk partiden sonra gelen yabancı satırlar da toplanır")
    void rowsFetchedAfterFirstBatchAreObserved() throws Exception {
        // Seed order is A then B and the query has no sort, so with actor A the foreign (B) rows are
        // scanned only when later batches are fetched.
        int rows = guard.callAs(TENANT_A, () -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (java.sql.PreparedStatement ps = connection.prepareStatement("SELECT id FROM invoice")) {
                    ps.setFetchSize(1);
                    int count = 0;
                    try (java.sql.ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            count++;
                        }
                    }
                    return count;
                } finally {
                    // Pools other than Hikari return the connection as it is: a transaction left open
                    // here holds a share lock on invoice and blocks the next observer install.
                    connection.rollback();
                    connection.setAutoCommit(true);
                }
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        });

        report("harvestedRows", guard.harvestedRows().toString());
        assertThat(rows).isEqualTo(4);
        assertThat(guard.harvestedRows()).as("foreign rows really arrived after the first batch")
                .containsKey(TenantGuard.HarvestPoint.FETCH);
        assertThat(guard.findings(Rule.ACTOR_MISMATCH))
                .flatExtracting(Finding::tenants)
                .contains(TENANT_B);
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
