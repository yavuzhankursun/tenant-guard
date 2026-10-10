package dev.tenantguard;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** The self-check's own preconditions; the blind setups against Postgres are in {@link SelfCheckBlindnessTest}. */
class SelfCheckTest {

    @Test
    void refusesAnInactiveGuard() {
        assertThatThrownBy(() -> SelfCheck.verify(mock(DataSource.class), new TenantGuard()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("needs an active guard");
    }

    @Test
    void failsWhenTheCurrentRoleIsUnknown() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet noRow = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(noRow);
        TenantGuard guard = new TenantGuard();
        guard.activate();

        assertThatThrownBy(() -> SelfCheck.verify(dataSource, guard))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current role not found in pg_roles");
    }
}
