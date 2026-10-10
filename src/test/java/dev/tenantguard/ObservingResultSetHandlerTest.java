package dev.tenantguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.tenantguard.TenantGuard.HarvestPoint;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The result set proxy against a scripted driver result: notices of later cursor fetches land on
 * its chain. Like pgjdbc, the fake refuses getWarnings once closed.
 */
class ObservingResultSetHandlerTest {

    private static final RowRead A = new RowRead("invoice", "A");

    /** A driver-specific type the proxy does not implement, e.g. PgResultSet. */
    interface DriverResultSet extends ResultSet {
    }

    private final TenantGuard guard = WarningChain.trustingGuard();
    private final WarningChain chain = new WarningChain();
    private final DriverResultSet driver = mock(DriverResultSet.class);
    private final Statement owner = mock(Statement.class);
    private boolean closed;
    private ResultSet observed;

    @BeforeEach
    void setUp() throws SQLException {
        when(driver.getWarnings()).thenAnswer(i -> {
            if (closed) {
                throw new SQLException("This ResultSet is closed.");
            }
            return chain.head();
        });
        when(driver.isClosed()).thenAnswer(i -> closed);
        doAnswer(i -> closed = true).when(driver).close();
        doAnswer(i -> {
            chain.clear();
            return null;
        }).when(driver).clearWarnings();
        observed = ObservingResultSetHandler.wrap(driver, owner, guard, "SELECT id FROM invoice");
    }

    @Test
    void navigationHarvestsTheNoticesItsFetchBrought() throws SQLException {
        when(driver.next()).thenAnswer(i -> {
            chain.notice("invoice", "A");
            return true;
        });

        assertThat(observed.next()).isTrue();

        // Recorded right away: a statement closed without closing its result set never reaches our close().
        assertThat(guard.rowReads()).containsOnly(entry(A, 1L));
        assertThat(guard.harvestedRows()).containsOnly(entry(HarvestPoint.FETCH, 1L));
    }

    @Test
    void closeHarvestsNoticesThatArrivedOutsideTheProxy() throws SQLException {
        // e.g. rows navigated through rs.unwrap(PgResultSet.class): the raw result shares the chain.
        chain.notice("invoice", "A");

        observed.close();

        assertThat(guard.rowReads()).containsOnly(entry(A, 1L));
    }

    @Test
    void callsOnAClosedResultSetStillWork() throws SQLException {
        observed.close();
        observed.close();

        assertThat(observed.isClosed()).isTrue();
    }

    @Test
    void clearWarningsHarvestsBeforeTheChainIsDropped() throws SQLException {
        chain.notice("invoice", "A");

        observed.clearWarnings();

        assertThat((Object) chain.head()).isNull();
        assertThat(guard.rowReads()).containsOnly(entry(A, 1L));
    }

    @Test
    void getStatementReturnsTheObservingStatement() throws SQLException {
        assertThat(observed.getStatement()).isSameAs(owner);
    }

    @Test
    void unwrapToAJdbcInterfaceStaysObserved() throws SQLException {
        when(driver.unwrap(ResultSet.class)).thenReturn(driver);

        assertThat(observed.unwrap(ResultSet.class)).isSameAs(observed);
    }

    @Test
    void unwrapToADriverTypeReachesTheDriver() throws SQLException {
        when(driver.unwrap(DriverResultSet.class)).thenReturn(driver);

        assertThat(observed.unwrap(DriverResultSet.class)).isSameAs(driver);
    }
}
