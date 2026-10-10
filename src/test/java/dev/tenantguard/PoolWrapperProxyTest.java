package dev.tenantguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

/**
 * Pool behaviours {@code PoolMatrixTest} found against real pools, scripted here without a database.
 * Each case failed before the matching core fix.
 */
class PoolWrapperProxyTest {

    private static final RowRead A = new RowRead("invoice", "A");
    private static final RowRead B = new RowRead("invoice", "B");
    private static final String SQL = "SELECT * FROM invoice WHERE tenant_id = ?";

    private final TenantGuard guard = WarningChain.trustingGuard();
    private final WarningChain chain = new WarningChain();
    private final Connection driverConnection = mock(Connection.class);
    private final Connection connection = ObservingConnectionHandler.wrap(driverConnection, guard);

    /**
     * c3p0 {@code maxStatements}, DBCP2 {@code poolPreparedStatements}, tomcat-jdbc {@code StatementCache}
     * and vibur hand the next borrower the same driver statement, warning chain included. Those notices
     * were harvested by the previous proxy; counting them again would charge them to the new actor.
     */
    @Test
    void aCachedStatementsLeftoverChainIsNotChargedToTheNextBorrower() throws SQLException {
        Statement cached = mock(Statement.class);
        when(cached.getWarnings()).thenAnswer(i -> chain.head());
        when(cached.executeQuery(anyString())).thenAnswer(i -> {
            chain.clear();
            chain.notice("invoice", "B");
            return null;
        });
        when(driverConnection.createStatement()).thenReturn(cached);
        chain.notice("invoice", "A");

        guard.runAs("B", () -> {
            try (Statement unread = connection.createStatement()) {
                assertThat(unread).isNotNull();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            try (Statement s = connection.createStatement()) {
                s.executeQuery(SQL);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(guard.rowReads()).containsOnly(entry(B, 1L));
        assertThat(guard.findings(Finding.Rule.ACTOR_MISMATCH)).isEmpty();
    }

    /**
     * DBCP2 hands out a new {@code DelegatingResultSet} per {@code executeQuery} and {@code getResultSet},
     * and its {@code unwrap(ResultSet.class)} returns the wrapper itself, so wrapper identity cannot tell
     * that both sit on one driver result. JDBC can: until the next execute or {@code getMoreResults},
     * {@code getResultSet} returns the result the execute produced.
     */
    @Test
    void twoPoolWrappersOfOneResultAreObservedOnce() throws SQLException {
        WarningChain fetched = new WarningChain();
        ResultSet first = selfUnwrapping(fetched);
        ResultSet second = selfUnwrapping(fetched);
        Statement driver = mock(Statement.class);
        when(driverConnection.createStatement()).thenReturn(driver);
        when(driver.executeQuery(anyString())).thenReturn(first);
        when(driver.getResultSet()).thenReturn(second);
        // Both wrappers move one driver cursor: one row, whose fetch brings A's notice.
        AtomicBoolean fetchedRow = new AtomicBoolean();
        Answer<Boolean> driverNext = i -> {
            if (fetchedRow.getAndSet(true)) {
                return false;
            }
            fetched.notice("invoice", "A");
            return true;
        };
        when(first.next()).thenAnswer(driverNext);
        when(second.next()).thenAnswer(driverNext);

        try (Statement s = connection.createStatement()) {
            ResultSet viaExecute = s.executeQuery(SQL);
            ResultSet viaGetter = s.getResultSet();
            assertThat(viaGetter.next()).isTrue();
            assertThat(viaExecute.next()).isFalse();
        }

        assertThat(guard.rowReads()).containsOnly(entry(A, 1L));
    }

    @Test
    void getMoreResultsMovesToANewlyObservedResult() throws SQLException {
        WarningChain fetched = new WarningChain();
        ResultSet first = selfUnwrapping(new WarningChain());
        ResultSet next = selfUnwrapping(fetched);
        Statement driver = mock(Statement.class);
        when(driverConnection.createStatement()).thenReturn(driver);
        when(driver.execute(anyString())).thenReturn(true);
        when(driver.getResultSet()).thenReturn(first, next);
        when(driver.getMoreResults()).thenReturn(true);
        when(next.next()).thenAnswer(i -> {
            fetched.notice("invoice", "A");
            return true;
        });

        try (Statement s = connection.createStatement()) {
            s.execute("SELECT 1; " + SQL);
            ResultSet one = s.getResultSet();
            assertThat(s.getMoreResults()).isTrue();
            ResultSet two = s.getResultSet();
            assertThat(two).isNotSameAs(one);
            assertThat(two.next()).isTrue();
        }

        assertThat(guard.rowReads()).containsOnly(entry(A, 1L));
    }

    /**
     * Re-executing a statement closes its previous result in pgjdbc, but c3p0's result wrapper still
     * says {@code isClosed() == false} while its {@code getWarnings} throws. Closing it must not fail
     * the application; the rows were harvested on navigation.
     */
    @Test
    void closingAResultTheDriverClosedUnderneathDoesNotThrow() throws SQLException {
        ResultSet stale = mock(ResultSet.class);
        when(stale.isClosed()).thenReturn(false);
        when(stale.getWarnings()).thenThrow(new SQLException("This ResultSet is closed."));
        ResultSet observed = ObservingResultSetHandler.wrap(stale, mock(Statement.class), guard, SQL);

        assertThatCode(observed::close).doesNotThrowAnyException();
        verify(stale).close();
    }

    private static ResultSet selfUnwrapping(WarningChain warnings) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.unwrap(ResultSet.class)).thenReturn(rs);
        when(rs.getWarnings()).thenAnswer(i -> warnings.head());
        return rs;
    }
}
