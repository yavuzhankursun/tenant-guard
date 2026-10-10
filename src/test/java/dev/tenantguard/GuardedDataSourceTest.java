package dev.tenantguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GuardedDataSourceTest {

    /** A pool-specific type, e.g. HikariDataSource. */
    interface PoolDataSource extends DataSource {
    }

    private final TenantGuard guard = new TenantGuard();
    private final DataSource delegate = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final Statement setup = mock(Statement.class);
    private final GuardedDataSource guarded = new GuardedDataSource(delegate, guard, ObserverInstaller.ROLE);

    @BeforeEach
    void setUp() throws SQLException {
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(setup);
    }

    @Test
    void activeGuardSwitchesToTheObservedRoleAndLetsNoticesThrough() throws SQLException {
        guard.activate();

        guarded.getConnection();

        verify(setup).execute("SET ROLE tg_app; SET client_min_messages = notice");
    }

    @Test
    void inactiveGuardResetsTheRole() throws SQLException {
        guarded.getConnection();

        verify(setup).execute("RESET ROLE");
    }

    @Test
    void aFailedSessionSetupClosesTheConnection() throws SQLException {
        SQLException refused = new SQLException("permission denied to set role");
        when(setup.execute(anyString())).thenThrow(refused);

        assertThatThrownBy(guarded::getConnection).isSameAs(refused);
        verify(connection).close();
    }

    @Test
    void connectionsComeBackObserved() throws SQLException {
        Connection observed = guarded.getConnection();

        assertThat(observed).isNotSameAs(connection);
        assertThat(observed.unwrap(Connection.class)).isSameAs(observed);
    }

    @Test
    void unwrapsToItselfOrDelegates() throws SQLException {
        PoolDataSource pool = mock(PoolDataSource.class);
        when(delegate.unwrap(PoolDataSource.class)).thenReturn(pool);
        when(delegate.isWrapperFor(PoolDataSource.class)).thenReturn(true);

        assertThat(guarded.unwrap(GuardedDataSource.class)).isSameAs(guarded);
        assertThat(guarded.unwrap(PoolDataSource.class)).isSameAs(pool);
        assertThat(guarded.isWrapperFor(GuardedDataSource.class)).isTrue();
        assertThat(guarded.isWrapperFor(PoolDataSource.class)).isTrue();
        assertThat(guarded.isWrapperFor(Runnable.class)).isFalse();
    }

    @Test
    void plumbingIsDelegated() throws SQLException {
        PrintWriter writer = new PrintWriter(new StringWriter());
        Logger logger = Logger.getLogger("pool");
        when(delegate.getLogWriter()).thenReturn(writer);
        when(delegate.getLoginTimeout()).thenReturn(7);
        when(delegate.getParentLogger()).thenReturn(logger);

        guarded.setLogWriter(writer);
        guarded.setLoginTimeout(7);

        verify(delegate).setLogWriter(writer);
        verify(delegate).setLoginTimeout(7);
        assertThat(guarded.getLogWriter()).isSameAs(writer);
        assertThat(guarded.getLoginTimeout()).isEqualTo(7);
        assertThat(guarded.getParentLogger()).isSameAs(logger);
    }
}
