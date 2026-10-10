package dev.tenantguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.tenantguard.TenantGuard.HarvestPoint;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The statement proxy against a scripted driver statement. Like pgjdbc, the fake clears its chain at
 * the start of every execute and refuses getWarnings once closed.
 */
class ObservingStatementHandlerTest {

    private static final RowRead A = new RowRead("invoice", "A");
    private static final RowRead B = new RowRead("invoice", "B");

    private final TenantGuard guard = new TenantGuard();
    private final WarningChain chain = new WarningChain();
    private final Connection driverConnection = mock(Connection.class);
    private final ResultSet driverResult = mock(ResultSet.class);
    private boolean closed;
    private Connection connection;

    @BeforeEach
    void setUp() throws SQLException {
        when(driverResult.unwrap(ResultSet.class)).thenReturn(driverResult);
        connection = ObservingConnectionHandler.wrap(driverConnection, guard);
    }

    /** A driver statement whose executes read one row of {@code tenant}. */
    private <T extends Statement> T scripted(Class<T> type, String tenant) throws SQLException {
        T statement = mock(type);
        when(statement.getWarnings()).thenAnswer(i -> {
            if (closed) {
                throw new SQLException("This statement has been closed.");
            }
            return chain.head();
        });
        when(statement.isClosed()).thenAnswer(i -> closed);
        doAnswer(i -> closed = true).when(statement).close();
        doAnswer(i -> {
            chain.clear();
            return null;
        }).when(statement).clearWarnings();
        when(statement.executeQuery(anyString())).thenAnswer(i -> execute(tenant));
        if (statement instanceof PreparedStatement prepared) {
            when(prepared.executeQuery()).thenAnswer(i -> execute(tenant));
        }
        return statement;
    }

    private ResultSet execute(String tenant) {
        chain.clear();
        chain.notice("invoice", tenant);
        return driverResult;
    }

    private Statement statement(String tenant) throws SQLException {
        Statement driver = scripted(Statement.class, tenant);
        when(driverConnection.createStatement()).thenReturn(driver);
        return connection.createStatement();
    }

    @Test
    void executeRecordsTheRowsUnderTheExecutedSql() throws SQLException {
        try (Statement s = statement("A")) {
            s.executeQuery("SELECT * FROM invoice");
        }

        assertThat(guard.observations()).singleElement()
                .satisfies(o -> assertThat(o.sql()).isEqualTo("SELECT * FROM invoice"));
        assertThat(guard.harvestedRows()).containsOnly(entry(HarvestPoint.EXECUTE, 1L));
    }

    @Test
    void preparedExecuteWithoutArgumentsKeepsThePreparedSql() throws SQLException {
        PreparedStatement driver = scripted(PreparedStatement.class, "A");
        when(driverConnection.prepareStatement("SELECT * FROM invoice WHERE id = ?")).thenReturn(driver);

        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM invoice WHERE id = ?")) {
            ps.executeQuery();
        }

        assertThat(guard.observations()).singleElement()
                .satisfies(o -> assertThat(o.sql()).isEqualTo("SELECT * FROM invoice WHERE id = ?"));
    }

    @Test
    void noticesStillOnTheChainSurviveTheDriverClearingItOnTheNextExecute() throws SQLException {
        try (Statement s = statement("B")) {
            // Left by an execute the proxy did not see, e.g. on s.unwrap(PgStatement.class).
            chain.notice("invoice", "A");
            s.executeQuery("SELECT * FROM invoice");
        }

        assertThat(guard.rowReads()).containsOnly(entry(A, 1L), entry(B, 1L));
        assertThat(guard.harvestedRows())
                .containsOnly(entry(HarvestPoint.CLEAR_WARNINGS, 1L), entry(HarvestPoint.EXECUTE, 1L));
    }

    @Test
    void clearWarningsHarvestsBeforeTheChainIsDropped() throws SQLException {
        try (Statement s = statement("A")) {
            chain.notice("invoice", "A");
            s.clearWarnings();
            assertThat((Object) chain.head()).isNull();
        }

        assertThat(guard.harvestedRows()).containsOnly(entry(HarvestPoint.CLEAR_WARNINGS, 1L));
    }

    @Test
    void closeHarvestsWhatIsLeftAndOtherCallsDoNotChangeTheSql() throws SQLException {
        Statement s = statement("B");
        s.executeQuery("SELECT * FROM invoice");
        s.setCursorName("not_sql");
        chain.notice("invoice", "A");

        s.close();

        assertThat(guard.observations()).extracting(Observation::sql).containsOnly("SELECT * FROM invoice");
        assertThat(guard.harvestedRows())
                .containsOnly(entry(HarvestPoint.EXECUTE, 1L), entry(HarvestPoint.CLOSE, 1L));
    }

    @Test
    void closingTwiceIsANoOp() throws SQLException {
        Statement s = statement("A");
        s.close();
        s.close();

        assertThat(s.isClosed()).isTrue();
    }

    @Test
    void aResultSetThatCannotUnwrapIsStillObserved() throws SQLException {
        when(driverResult.unwrap(ResultSet.class)).thenThrow(new SQLException("not a wrapper"));

        try (Statement s = statement("A")) {
            ResultSet rs = s.executeQuery("SELECT * FROM invoice");

            assertThat(rs).isNotNull().isNotSameAs(driverResult);
            assertThat(rs.getStatement()).isSameAs(s);
        }
    }

    @Test
    void getConnectionReturnsTheObservingConnection() throws SQLException {
        try (Statement s = statement("A")) {
            assertThat(s.getConnection()).isSameAs(connection);
        }
    }
}
