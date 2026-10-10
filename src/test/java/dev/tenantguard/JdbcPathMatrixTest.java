package dev.tenantguard;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tenantguard.Finding.Rule;
import dev.tenantguard.sample.Invoice;
import dev.tenantguard.sample.InvoiceRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Types;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.PGStatement;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.jdbc.PgStatement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every way an application can reach the database must stay observed. Each case reads all of
 * {@code invoice} as actor B; the guard has to report A's rows exactly once, whatever the path.
 * Seed order is A then B, so with fetchSize=1 A's second row only arrives in a later cursor batch.
 */
class JdbcPathMatrixTest extends GuardedTestBase {

    private static final String SCAN = "SELECT id, number FROM invoice";
    private static final String TOUCH_ALL = "UPDATE invoice SET number = number";
    private static final String COUNT_FUNCTION = "tg_test_invoice_count";
    private static final RowRead A_INVOICE = new RowRead("invoice", TENANT_A);
    /** Fetch-size batches split one read into several observations, so only "at least one" is stable. */
    private static final int ANY = -1;

    @Autowired
    InvoiceRepository invoices;

    @Autowired
    PlatformTransactionManager transactionManager;

    @PersistenceContext
    EntityManager entityManager;

    @FunctionalInterface
    interface Access {
        void run(JdbcPathMatrixTest test) throws Exception;
    }

    @FunctionalInterface
    interface SqlWork {
        void run(Connection connection) throws SQLException;
    }

    record JdbcPath(String name, long aRows, int findings, Access access) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<JdbcPath> paths() {
        return Stream.of(statementPaths(), batchPaths(), cursorPaths(), springPaths(), hibernatePaths(), unwrapPaths())
                .flatMap(s -> s);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("paths")
    void unfilteredReadAsBIsObserved(JdbcPath path) {
        guard.callAs(TENANT_B, () -> unchecked(() -> {
            path.access().run(this);
            return null;
        }));

        report(path.name(), "harvested=" + guard.harvestedRows() + " reads=" + guard.rowReads());
        assertThat(guard.findings(Rule.ACTOR_MISMATCH)).as("ACTOR_MISMATCH on A")
                .anySatisfy(f -> assertThat(f.tenants()).contains(TENANT_A));
        if (path.findings() != ANY) {
            assertThat(guard.findings(Rule.ACTOR_MISMATCH)).as("separate findings").hasSize(path.findings());
        }
        assertThat(guard.rowReads().getOrDefault(A_INVOICE, 0L)).as("observed A rows").isEqualTo(path.aRows());
    }

    @Test
    @DisplayName("Kör nokta: Connection.unwrap(PgConnection.class) ham sürücü bağlantısını verir, onun statement'ları gözlenmez")
    void driverSpecificUnwrapIsADocumentedBlindSpot() {
        int rows = guard.callAs(TENANT_B, () -> unchecked(() -> {
            int[] read = new int[1];
            onConnection(c -> {
                PgConnection raw = c.unwrap(PgConnection.class);
                try (Statement s = raw.createStatement(); ResultSet rs = s.executeQuery(SCAN)) {
                    read[0] = drain(rs);
                }
            });
            return read[0];
        }));

        assertThat(rows).as("the leak really happened").isEqualTo(4);
        assertThat(guard.observations()).as("but nothing was observed").isEmpty();
        assertThat(guard.findings()).isEmpty();
    }

    @Test
    @DisplayName("Kör nokta: ham PgStatement üzerinden cursor (fetchSize=1): sonraki partilerin satırları gözlenmez")
    void rawDriverStatementCursorIsAPartialBlindSpot() {
        int rows = guard.callAs(TENANT_B, () -> unchecked(() -> {
            int[] read = new int[1];
            inTransaction(c -> {
                try (Statement s = c.createStatement()) {
                    PgStatement raw = s.unwrap(PgStatement.class);
                    raw.setFetchSize(1);
                    read[0] = drain(raw.executeQuery(SCAN));
                }
            });
            return read[0];
        }));

        assertThat(rows).as("the leak really happened").isEqualTo(4);
        // Only the first batch lands on the statement chain, which the proxy harvests on close.
        assertThat(guard.rowReads().getOrDefault(A_INVOICE, 0L)).as("observed A rows").isEqualTo(1);
        assertThat(guard.harvestedRows()).as("later batches, carried by the raw ResultSet, are unobserved")
                .doesNotContainKey(TenantGuard.HarvestPoint.FETCH);
    }

    @Test
    @DisplayName("Postgres: kendi kolonunu okuyan UPDATE politikayı satır başına iki kez çalıştırır (eski satır + yeni satır)")
    void updateReadingItsOwnColumnRunsThePolicyTwicePerRow() throws SQLException {
        // SET number = number needs SELECT rights, so the SELECT policy is also checked on the new row.
        assertThat(rawNoticeCount(TOUCH_ALL, RowRead.Kind.READ)).isEqualTo(8);
        assertThat(rawNoticeCount("UPDATE invoice SET amount = 0", RowRead.Kind.READ)).isEqualTo(4);
        // WITH CHECK runs once per new row, whether or not the statement reads its own columns.
        assertThat(rawNoticeCount(TOUCH_ALL, RowRead.Kind.WRITE)).isEqualTo(4);
        assertThat(rawNoticeCount("UPDATE invoice SET amount = 0", RowRead.Kind.WRITE)).isEqualTo(4);
    }

    /** Bypasses the guard entirely: what the server really sends, as the observed role. */
    private static int rawNoticeCount(String sql, RowRead.Kind kind) throws SQLException {
        try (Connection owner = ownerConnection(); Statement s = owner.createStatement()) {
            owner.setAutoCommit(false);
            s.execute("SET LOCAL ROLE " + ObserverInstaller.ROLE);
            s.execute(sql);
            int notices = 0;
            for (SQLWarning w = s.getWarnings(); w != null; w = w.getNextWarning()) {
                // Any nonce: this raw connection bypasses the guard that knows the install's nonce.
                notices += RowRead.parse(w, nonce -> true).filter(row -> row.kind() == kind).isPresent() ? 1 : 0;
            }
            owner.rollback();
            return notices;
        }
    }

    // --- plain Statement / PreparedStatement / CallableStatement ---------------------------------

    private static Stream<JdbcPath> statementPaths() {
        return Stream.of(
                path("Statement.executeQuery", 2, JdbcPathMatrixTest::statementExecuteQuery),
                path("Statement.execute + getResultSet", 2, JdbcPathMatrixTest::statementExecute),
                path("Statement.execute multi-result + getMoreResults", 2, JdbcPathMatrixTest::statementMoreResults),
                path("PreparedStatement.executeQuery", 2, JdbcPathMatrixTest::preparedExecuteQuery),
                new JdbcPath("PreparedStatement.executeQuery twice", 4, 2, JdbcPathMatrixTest::preparedTwice),
                path("PreparedStatement.execute + getResultSet", 2, JdbcPathMatrixTest::preparedExecute),
                path("CallableStatement via prepareCall, plain SQL", 2, JdbcPathMatrixTest::callablePlainSql),
                path("CallableStatement { ? = call fn() }", 2, JdbcPathMatrixTest::callableFunction),
                path("DataSource.getConnection(user, password)", 2, JdbcPathMatrixTest::credentialsConnection));
    }

    void statementExecuteQuery() throws SQLException {
        onConnection(c -> {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(SCAN)) {
                drain(rs);
            }
        });
    }

    void statementExecute() throws SQLException {
        onConnection(c -> {
            try (Statement s = c.createStatement()) {
                assertThat(s.execute(SCAN)).isTrue();
                try (ResultSet rs = s.getResultSet()) {
                    drain(rs);
                }
            }
        });
    }

    void statementMoreResults() throws SQLException {
        onConnection(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("SELECT 1; " + SCAN);
                assertThat(s.getMoreResults()).isTrue();
                try (ResultSet rs = s.getResultSet()) {
                    drain(rs);
                }
            }
        });
    }

    void preparedExecuteQuery() throws SQLException {
        onConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(SCAN); ResultSet rs = ps.executeQuery()) {
                drain(rs);
            }
        });
    }

    void preparedTwice() throws SQLException {
        onConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(SCAN)) {
                drain(ps.executeQuery());
                drain(ps.executeQuery());
            }
        });
    }

    void preparedExecute() throws SQLException {
        onConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(SCAN)) {
                assertThat(ps.execute()).isTrue();
                drain(ps.getResultSet());
            }
        });
    }

    void callablePlainSql() throws SQLException {
        onConnection(c -> {
            try (CallableStatement cs = c.prepareCall(SCAN); ResultSet rs = cs.executeQuery()) {
                drain(rs);
            }
        });
    }

    void callableFunction() throws SQLException {
        try (Connection owner = ownerConnection(); Statement ddl = owner.createStatement()) {
            ddl.execute("CREATE OR REPLACE FUNCTION " + COUNT_FUNCTION
                    + "() RETURNS bigint LANGUAGE sql STABLE AS 'SELECT count(*) FROM invoice'");
        }
        onConnection(c -> {
            try (CallableStatement cs = c.prepareCall("{ ? = call " + COUNT_FUNCTION + "() }")) {
                cs.registerOutParameter(1, Types.BIGINT);
                cs.execute();
                assertThat(cs.getLong(1)).isEqualTo(4);
            }
        });
    }

    void credentialsConnection() throws SQLException {
        // Hikari rejects getConnection(user, password), so use a plain driver DataSource behind the guard.
        DataSource direct = new GuardedDataSource(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl()), guard, ObserverInstaller.ROLE);
        try (Connection c = direct.getConnection(POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery(SCAN)) {
            drain(rs);
        }
    }

    // --- writes: the FOR ALL policy's USING clause also filters UPDATE's scan. SET number = number
    // reads the column, so Postgres checks the new row against the policy too: 2 notices per row.

    private static Stream<JdbcPath> batchPaths() {
        return Stream.of(
                path("Statement.executeUpdate (UPDATE)", 4, t -> t.statementBatch(s -> s.executeUpdate(TOUCH_ALL))),
                path("Statement.executeBatch (UPDATE)", 4, t -> t.statementBatch(s -> {
                    s.addBatch(TOUCH_ALL);
                    s.executeBatch();
                })),
                path("Statement.executeLargeBatch (UPDATE)", 4, t -> t.statementBatch(s -> {
                    s.addBatch(TOUCH_ALL);
                    s.executeLargeBatch();
                })),
                path("PreparedStatement.executeBatch, addBatch twice (UPDATE)", 8,
                        JdbcPathMatrixTest::preparedBatch));
    }

    @FunctionalInterface
    interface StatementWork {
        void run(Statement statement) throws SQLException;
    }

    void statementBatch(StatementWork work) throws SQLException {
        onConnection(c -> {
            try (Statement s = c.createStatement()) {
                work.run(s);
            }
        });
    }

    void preparedBatch() throws SQLException {
        onConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(TOUCH_ALL)) {
                ps.addBatch();
                ps.addBatch();
                ps.executeBatch();
            }
        });
    }

    // --- server-side cursors and scrollable result sets ------------------------------------------

    private static Stream<JdbcPath> cursorPaths() {
        return Stream.of(
                path("Statement cursor, fetchSize=1 in transaction", 2, JdbcPathMatrixTest::statementCursor),
                path("PreparedStatement cursor, fetchSize=1 in transaction", 2, JdbcPathMatrixTest::preparedCursor),
                path("Scrollable ResultSet absolute/relative, fetchSize=1", 2, JdbcPathMatrixTest::scrollable),
                path("Statement cursor, executeQuery + getResultSet on the same result", 2,
                        JdbcPathMatrixTest::cursorResultFetchedTwice));
    }

    void statementCursor() throws SQLException {
        inTransaction(c -> {
            try (Statement s = c.createStatement()) {
                s.setFetchSize(1);
                try (ResultSet rs = s.executeQuery(SCAN)) {
                    assertThat(drain(rs)).isEqualTo(4);
                }
            }
        });
    }

    void preparedCursor() throws SQLException {
        inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(SCAN)) {
                ps.setFetchSize(1);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(drain(rs)).isEqualTo(4);
                }
            }
        });
    }

    void scrollable() throws SQLException {
        inTransaction(c -> {
            try (Statement s = c.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY)) {
                s.setFetchSize(1);
                try (ResultSet rs = s.executeQuery(SCAN)) {
                    assertThat(rs.absolute(4)).isTrue();
                    assertThat(rs.relative(-3)).isTrue();
                }
            }
        });
    }

    /** Two wrappers over one driver ResultSet must not count the same fetch notices twice. */
    void cursorResultFetchedTwice() throws SQLException {
        // c3p0 throws its own InternalError on a second wrapper of one result, guard or not:
        // PoolMatrixTest.c3p0RejectsTwoWrappersOfOneResult pins that behaviour.
        org.junit.jupiter.api.Assumptions.assumeFalse(
                dev.tenantguard.sample.pool.TestPool.of(System.getProperty(dev.tenantguard.sample.pool.TestPool.PROPERTY))
                        == dev.tenantguard.sample.pool.TestPool.C3P0,
                "c3p0 rejects executeQuery + getResultSet on one result (its own InternalError)");
        inTransaction(c -> {
            try (Statement s = c.createStatement()) {
                s.setFetchSize(1);
                try (ResultSet first = s.executeQuery(SCAN); ResultSet second = s.getResultSet()) {
                    assertThat(drain(second)).isEqualTo(4);
                    assertThat(first.next()).isFalse();
                }
            }
        });
    }

    // --- Spring JdbcTemplate -----------------------------------------------------------------------

    private static Stream<JdbcPath> springPaths() {
        return Stream.of(
                path("JdbcTemplate.query + RowCallbackHandler, fetchSize=1 in transaction", 2,
                        JdbcPathMatrixTest::jdbcTemplateCursor),
                path("JdbcTemplate.queryForStream", 2, t -> t.queryForStream(t.jdbc)),
                path("JdbcTemplate.queryForStream, fetchSize=1 in transaction", 2,
                        t -> t.transactions().executeWithoutResult(status -> t.queryForStream(t.cursorTemplate()))));
    }

    void jdbcTemplateCursor() {
        transactions().executeWithoutResult(status -> {
            int[] rows = new int[1];
            cursorTemplate().query(SCAN, (RowCallbackHandler) rs -> rows[0]++);
            assertThat(rows[0]).isEqualTo(4);
        });
    }

    void queryForStream(JdbcTemplate template) {
        try (Stream<Long> ids = template.queryForStream(SCAN, (rs, n) -> rs.getLong(1))) {
            assertThat(ids.count()).isEqualTo(4);
        }
    }

    private JdbcTemplate cursorTemplate() {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setFetchSize(1);
        return template;
    }

    // --- Hibernate / Spring Data -----------------------------------------------------------------

    private static Stream<JdbcPath> hibernatePaths() {
        return Stream.of(
                path("Hibernate InvoiceRepository.findAll()", 2, t -> assertThat(t.invoices.findAll()).hasSize(4)),
                path("Spring Data @Query JPQL", 2, t -> t.invoices.totalAmountOfAllTenants()),
                path("EntityManager.createNativeQuery", 2,
                        t -> assertThat(t.entityManager.createNativeQuery(SCAN).getResultList()).hasSize(4)),
                path("Query.getResultStream, fetchSize hint=1 in transaction", 2,
                        JdbcPathMatrixTest::hibernateResultStream),
                path("EntityManager.find(Invoice, A's id)", 1,
                        t -> assertThat(t.entityManager.find(Invoice.class, t.invoiceOfA)).isNotNull()));
    }

    void hibernateResultStream() {
        transactions().executeWithoutResult(status -> {
            try (Stream<Invoice> rows = entityManager.createQuery("select i from Invoice i", Invoice.class)
                    .setHint("org.hibernate.fetchSize", 1)
                    .getResultStream()) {
                assertThat(rows.count()).isEqualTo(4);
            }
        });
    }

    // --- unwrap: JDBC interfaces must come back observed -----------------------------------------

    private static Stream<JdbcPath> unwrapPaths() {
        return Stream.of(
                path("Connection.unwrap(Connection.class)", 2, JdbcPathMatrixTest::unwrapConnection),
                path("Statement.unwrap(Statement.class), fetchSize=1", 2, JdbcPathMatrixTest::unwrapStatement),
                path("ResultSet.unwrap(ResultSet.class), fetchSize=1", 2, JdbcPathMatrixTest::unwrapResultSet),
                path("Statement.unwrap(PGStatement.class), executed via the proxy", 2,
                        JdbcPathMatrixTest::unwrapDriverStatementForTuning),
                path("Raw PgStatement.executeQuery after a proxied execute, seen at close", 4,
                        JdbcPathMatrixTest::rawExecuteAfterProxiedExecute));
    }

    void unwrapConnection() throws SQLException {
        onConnection(c -> {
            try (Statement s = c.unwrap(Connection.class).createStatement(); ResultSet rs = s.executeQuery(SCAN)) {
                drain(rs);
            }
        });
    }

    void unwrapStatement() throws SQLException {
        inTransaction(c -> {
            try (Statement s = c.createStatement()) {
                Statement unwrapped = s.unwrap(Statement.class);
                unwrapped.setFetchSize(1);
                try (ResultSet rs = unwrapped.executeQuery(SCAN)) {
                    assertThat(drain(rs)).isEqualTo(4);
                }
            }
        });
    }

    void unwrapResultSet() throws SQLException {
        inTransaction(c -> {
            try (Statement s = c.createStatement()) {
                s.setFetchSize(1);
                try (ResultSet rs = s.executeQuery(SCAN)) {
                    assertThat(drain(rs.unwrap(ResultSet.class))).isEqualTo(4);
                }
            }
        });
    }

    /** The common reason to unwrap a statement is driver tuning; execution stays on the proxy. */
    void unwrapDriverStatementForTuning() throws SQLException {
        onConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(SCAN)) {
                ps.unwrap(PGStatement.class).setPrepareThreshold(1);
                drain(ps.executeQuery());
            }
        });
    }

    /** The driver clears the chain on the raw execute; the proxy must not skip the new notices. */
    void rawExecuteAfterProxiedExecute() throws SQLException {
        onConnection(c -> {
            try (Statement s = c.createStatement()) {
                drain(s.executeQuery(SCAN));
                drain(s.unwrap(PgStatement.class).executeQuery(SCAN));
            }
        });
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static JdbcPath path(String name, long aRows, Access access) {
        return new JdbcPath(name, aRows, ANY, access);
    }

    void onConnection(SqlWork work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            work.run(connection);
        }
    }

    /** pgjdbc only opens a server-side cursor when autocommit is off. */
    void inTransaction(SqlWork work) throws SQLException {
        onConnection(c -> {
            c.setAutoCommit(false);
            try {
                work.run(c);
            } finally {
                c.rollback();
                c.setAutoCommit(true);
            }
        });
    }

    private TransactionTemplate transactions() {
        return new TransactionTemplate(transactionManager);
    }

    private static int drain(ResultSet rs) throws SQLException {
        int rows = 0;
        while (rs.next()) {
            rows++;
        }
        return rows;
    }

    @FunctionalInterface
    private interface Checked<T> {
        T get() throws Exception;
    }

    private static <T> T unchecked(Checked<T> work) {
        try {
            return work.get();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void report(String key, String value) {
        System.out.printf("[matrix] %s: %s%n", key, value);
    }
}
