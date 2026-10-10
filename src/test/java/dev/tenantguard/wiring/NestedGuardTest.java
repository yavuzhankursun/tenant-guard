package dev.tenantguard.wiring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.tenantguard.GuardedDataSource;
import dev.tenantguard.ObserverInstaller;
import dev.tenantguard.TenantGuard;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Two guards on one path (a routing bean over guarded pool beans) and a guard the extension switched
 * off, without a database: the session setup runs once and the connection is proxied once.
 */
class NestedGuardTest {

    private final TenantGuard guard = new TenantGuard();
    private final DataSource pool = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final Statement setup = mock(Statement.class);
    private final GuardedDataSource inner = new GuardedDataSource(pool, guard, ObserverInstaller.ROLE);

    @BeforeEach
    void setUp() throws SQLException {
        when(pool.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(setup);
        guard.activate();
    }

    @Test
    void anOuterGuardHandsOutTheInnerGuardsConnectionUntouched() throws SQLException {
        GuardedDataSource outer = new GuardedDataSource(inner, guard, ObserverInstaller.ROLE);

        Connection handedOut = outer.getConnection();

        verify(setup, times(1)).execute(anyString());
        verify(setup).execute("SET ROLE tg_app; SET client_min_messages = notice");
        assertThat(handedOut).isNotSameAs(connection);
    }

    @Test
    void aGuardSwitchedOffResetsTheRoleAndDoesNotObserve() throws SQLException {
        inner.setObserving(false);

        Connection handedOut = inner.getConnection();

        verify(setup).execute("RESET ROLE");
        verify(setup, times(0)).execute("SET ROLE tg_app; SET client_min_messages = notice");
        assertThat(handedOut).isSameAs(connection);
        assertThat(inner.isObserving()).isFalse();
    }

    @Test
    void anOuterGuardStillObservesWhenTheInnerOneIsSwitchedOff() throws SQLException {
        inner.setObserving(false);
        GuardedDataSource outer = new GuardedDataSource(inner, guard, ObserverInstaller.ROLE);

        Connection handedOut = outer.getConnection();

        verify(setup).execute("RESET ROLE");
        verify(setup).execute("SET ROLE tg_app; SET client_min_messages = notice");
        assertThat(handedOut).isNotSameAs(connection);
    }

    @Test
    void aFailingObservedCheckClosesTheConnection() throws SQLException {
        when(connection.isWrapperFor(any())).thenThrow(new SQLException("closed"));

        assertThatThrownBy(inner::getConnection).hasMessage("closed");
        verify(connection).close();
        verify(setup, times(0)).execute(anyString());
    }
}
