package dev.tenantguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import dev.tenantguard.sample.pool.TestPool;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The warning-chain cursor was verified against Hikari only, but every pool wraps JDBC objects its
 * own way: proxies per call, {@code unwrap} semantics, statement caches that hand the same driver
 * statement (and its warning chain) to the next borrower, cleanup on return. So each key leak path
 * runs through every pool, in one JVM, and must report A's rows exactly as often as they were read.
 *
 * <p>Pools are built directly (no Spring) with one connection, so "return and re-borrow" really lands
 * on the same physical connection and a statement cache really hits. A separate database keeps
 * Flyway's schema in the shared container untouched.
 */
class PoolMatrixTest {

    private static final String DATABASE = "tg_pool_matrix";
    private static final String SCAN = "SELECT id, number FROM invoice";
    private static final RowRead A_INVOICE = new RowRead("invoice", "A");
    private static final String BOTH_WRAPPERS = "cursor: executeQuery + getResultSet on one result";

    record PoolCase(TestPool pool, boolean statementCache) {
        @Override
        public String toString() {
            return pool + (statementCache ? "+cache" : "");
        }
    }

    /** The observer of the matrix database; every test's fresh guard trusts its notices. */
    private static ObserverInstaller.Installation installation;

    @FunctionalInterface
    interface Leak {
        void run(Fixture fixture) throws SQLException;
    }

    record LeakPath(String name, long aRows, Leak leak) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** One guarded pool; {@code backendPids} records which server session each borrow got. */
    record Fixture(DataSource dataSource, List<Integer> backendPids) {

        Connection borrow() throws SQLException {
            Connection c = dataSource.getConnection();
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT pg_backend_pid()")) {
                rs.next();
                backendPids.add(rs.getInt(1));
            }
            return c;
        }
    }

    @BeforeAll
    static void createObservedDatabase() throws SQLException {
        try (Connection admin = GuardedTestBase.ownerConnection(); Statement s = admin.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + DATABASE);
            s.execute("CREATE DATABASE " + DATABASE);
        }
        try (Connection owner = DriverManager.getConnection(url(), user(), password());
             Statement s = owner.createStatement()) {
            s.execute("CREATE TABLE invoice (id bigserial PRIMARY KEY, tenant_id text NOT NULL, number text NOT NULL)");
            installation = ObserverInstaller.install(owner, "tenant_id", List.of("invoice"));
            // A first: with fetchSize=1, A's second row only arrives in a later cursor batch.
            s.execute("INSERT INTO invoice (tenant_id, number) VALUES ('A', 'A-1'), ('A', 'A-2'), ('B', 'B-1'), ('B', 'B-2')");
        }
    }

    static Stream<PoolCase> pools() {
        return Stream.of(TestPool.values()).flatMap(pool -> pool.hasStatementCache()
                ? Stream.of(new PoolCase(pool, false), new PoolCase(pool, true))
                : Stream.of(new PoolCase(pool, false)));
    }

    static Stream<Arguments> matrix() {
        return pools().flatMap(pool -> leakPaths().map(path -> Arguments.of(pool, path)));
    }

    @ParameterizedTest(name = "{0} | {1}")
    @MethodSource("matrix")
    void everyLeakPathIsObservedExactlyOnceThroughEveryPool(PoolCase pool, LeakPath path) throws Exception {
        assumeFalse(pool.pool() == TestPool.C3P0 && path.name().equals(BOTH_WRAPPERS),
                "c3p0 itself throws InternalError here, guard or not: see c3p0RejectsTwoWrappersOfOneResult");
        TenantGuard guard = new TenantGuard();
        List<Integer> pids = withPool(pool, guard,
                fixture -> guard.runAs("B", () -> unchecked(() -> path.leak().run(fixture))));

        System.out.printf("[pool-matrix] %s | %s: reads=%s harvested=%s pids=%s%n",
                pool, path, guard.rowReads(), guard.harvestedRows(), pids);
        assertThat(guard.rowReads().getOrDefault(A_INVOICE, 0L)).as("observed A rows").isEqualTo(path.aRows());
        if (pool.pool() != TestPool.NONE) {
            assertThat(pids).as("one pooled session behind every borrow").containsOnly(pids.get(0));
        }
    }

    /**
     * A cached statement keeps its warning chain across borrows. If the next borrower's proxy read it,
     * tenant A's own, legitimate read would be charged to B: a false ACTOR_MISMATCH.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cachedPools")
    void aCachedStatementDoesNotChargeThePreviousTenantsReadToTheNext(PoolCase pool) throws Exception {
        TenantGuard guard = new TenantGuard();
        withPool(pool, guard, fixture -> {
            for (String tenant : List.of("A", "B")) {
                guard.runAs(tenant, () -> unchecked(() -> onConnection(fixture, c -> {
                    try (PreparedStatement ps = c.prepareStatement(SCAN + " WHERE tenant_id = ?")) {
                        ps.setString(1, tenant);
                        assertThat(drain(ps.executeQuery())).isEqualTo(2);
                    }
                })));
            }
        });

        assertThat(guard.findings()).as("each tenant read only its own rows").isEmpty();
        assertThat(guard.rowReads()).containsOnly(entry(A_INVOICE, 2L), entry(new RowRead("invoice", "B"), 2L));
    }

    /**
     * Statement caches do not reset {@code fetchSize} on return, so a later borrower's plain query on
     * the same SQL silently runs as a cursor. Rows are still counted exactly; only the findings split
     * per fetch batch (why ObserverScenariosTest #7's "single finding" fails under a cached pool).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cachedPools")
    void aFetchSizeCarriedByTheCacheSplitsFindingsButNotRowCounts(PoolCase pool) throws Exception {
        TenantGuard guard = new TenantGuard();
        int[] inherited = new int[1];
        withPool(pool, guard, fixture -> guard.runAs("B", () -> unchecked(() -> {
            inTransaction(fixture, PoolMatrixTest::preparedCursor);
            inTransaction(fixture, c -> {
                try (PreparedStatement ps = c.prepareStatement(SCAN)) {
                    inherited[0] = ps.getFetchSize();
                    assertThat(drain(ps.executeQuery())).isEqualTo(4);
                }
            });
        })));

        System.out.printf("[pool-matrix] %s | fetchSize inherited by the 2nd borrow: %d, harvested=%s%n",
                pool, inherited[0], guard.harvestedRows());
        assertThat(guard.rowReads().getOrDefault(A_INVOICE, 0L)).as("observed A rows").isEqualTo(4);
    }

    /** Proof the skipped matrix cell is c3p0's own behaviour: same calls, no guard, same InternalError. */
    @Test
    void c3p0RejectsTwoWrappersOfOneResult() throws Exception {
        DataSource raw = TestPool.C3P0.create(new TestPool.Settings(url(), user(), password(), 1, false));
        try (Connection c = raw.getConnection()) {
            Statement s = c.createStatement();
            ResultSet first = s.executeQuery(SCAN);
            s.getResultSet();
            first.close();
            assertThatThrownBy(s::close).isInstanceOf(InternalError.class)
                    .hasMessageContaining("Marking a ResultSet inactive that we did not know was opened");
        } finally {
            TestPool.close(raw);
        }
    }

    static Stream<PoolCase> cachedPools() {
        return pools().filter(PoolCase::statementCache);
    }

    @FunctionalInterface
    interface PoolWork {
        void run(Fixture fixture) throws Exception;
    }

    /** One connection, so a re-borrow lands on the same session and a statement cache really hits. */
    private static List<Integer> withPool(PoolCase pool, TenantGuard guard, PoolWork work) throws Exception {
        DataSource raw = pool.pool().create(new TestPool.Settings(url(), user(), password(), 1, pool.statementCache()));
        Fixture fixture = new Fixture(new GuardedDataSource(raw, guard, ObserverInstaller.ROLE), new ArrayList<>());
        try {
            guard.trust(installation);
            guard.activate();
            work.run(fixture);
        } finally {
            TestPool.close(raw);
        }
        return fixture.backendPids();
    }

    // --- leak paths: every one reads all of invoice as B --------------------------------------------

    static Stream<LeakPath> leakPaths() {
        return Stream.of(
                new LeakPath("Statement.executeQuery", 2, f -> onConnection(f, c -> {
                    try (Statement s = c.createStatement()) {
                        assertThat(drain(s.executeQuery(SCAN))).isEqualTo(4);
                    }
                })),
                new LeakPath("PreparedStatement.executeQuery twice", 4, f -> onConnection(f, c -> {
                    try (PreparedStatement ps = c.prepareStatement(SCAN)) {
                        drain(ps.executeQuery());
                        drain(ps.executeQuery());
                    }
                })),
                new LeakPath("Statement cursor, fetchSize=1 in transaction", 2, f -> inTransaction(f, c -> {
                    try (Statement s = c.createStatement()) {
                        s.setFetchSize(1);
                        assertThat(drain(s.executeQuery(SCAN))).isEqualTo(4);
                    }
                })),
                new LeakPath("PreparedStatement cursor, fetchSize=1 in transaction", 2,
                        f -> inTransaction(f, PoolMatrixTest::preparedCursor)),
                new LeakPath(BOTH_WRAPPERS, 2, f -> inTransaction(f, c -> {
                    try (Statement s = c.createStatement()) {
                        s.setFetchSize(1);
                        try (ResultSet first = s.executeQuery(SCAN); ResultSet second = s.getResultSet()) {
                            assertThat(drain(second)).isEqualTo(4);
                            assertThat(first.next()).isFalse();
                        }
                    }
                })),
                new LeakPath("Connection.unwrap(Connection.class)", 2, f -> onConnection(f, c -> {
                    try (Statement s = c.unwrap(Connection.class).createStatement()) {
                        drain(s.executeQuery(SCAN));
                    }
                })),
                new LeakPath("Statement.getConnection().createStatement()", 2, f -> onConnection(f, c -> {
                    try (Statement first = c.createStatement(); Statement s = first.getConnection().createStatement()) {
                        drain(s.executeQuery(SCAN));
                    }
                })),
                new LeakPath("ResultSet.getStatement().executeQuery()", 2, f -> onConnection(f, c -> {
                    try (Statement s = c.createStatement(); ResultSet one = s.executeQuery("SELECT 1")) {
                        drain(one.getStatement().executeQuery(SCAN));
                    }
                })),
                new LeakPath("Statement, returned to the pool, re-borrowed, Statement", 4,
                        f -> twice(f, g -> onConnection(g, c -> {
                            try (Statement s = c.createStatement()) {
                                drain(s.executeQuery(SCAN));
                            }
                        }))),
                new LeakPath("PreparedStatement, same SQL on two borrows (statement cache hit)", 4,
                        f -> twice(f, g -> onConnection(g, c -> {
                            try (PreparedStatement ps = c.prepareStatement(SCAN)) {
                                drain(ps.executeQuery());
                            }
                        }))),
                new LeakPath("PreparedStatement cursor fetchSize=1, same SQL on two borrows", 4,
                        f -> twice(f, g -> inTransaction(g, PoolMatrixTest::preparedCursor))),
                new LeakPath("PreparedStatement closed unread, same SQL re-borrowed and read", 2, f -> {
                    onConnection(f, c -> c.prepareStatement(SCAN).close());
                    onConnection(f, c -> {
                        try (PreparedStatement ps = c.prepareStatement(SCAN)) {
                            drain(ps.executeQuery());
                        }
                    });
                }));
    }

    // --- helpers ---------------------------------------------------------------------------------------

    @FunctionalInterface
    interface SqlWork {
        void run(Connection connection) throws SQLException;
    }

    private static void preparedCursor(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SCAN)) {
            ps.setFetchSize(1);
            assertThat(drain(ps.executeQuery())).isEqualTo(4);
        }
    }

    private static void twice(Fixture f, Leak leak) throws SQLException {
        leak.run(f);
        leak.run(f);
    }

    private static void onConnection(Fixture f, SqlWork work) throws SQLException {
        try (Connection c = f.borrow()) {
            work.run(c);
        }
    }

    private static void inTransaction(Fixture f, SqlWork work) throws SQLException {
        onConnection(f, c -> {
            c.setAutoCommit(false);
            try {
                work.run(c);
            } finally {
                c.rollback();
                c.setAutoCommit(true);
            }
        });
    }

    private static int drain(ResultSet rs) throws SQLException {
        int rows = 0;
        while (rs.next()) {
            rows++;
        }
        return rows;
    }

    private static String url() {
        return "jdbc:postgresql://%s:%d/%s".formatted(GuardedTestBase.POSTGRES.getHost(),
                GuardedTestBase.POSTGRES.getMappedPort(5432), DATABASE);
    }

    private static String user() {
        return GuardedTestBase.POSTGRES.getUsername();
    }

    private static String password() {
        return GuardedTestBase.POSTGRES.getPassword();
    }

    @FunctionalInterface
    private interface Checked {
        void run() throws Exception;
    }

    private static void unchecked(Checked work) {
        try {
            work.run();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
