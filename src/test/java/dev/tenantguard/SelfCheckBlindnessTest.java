package dev.tenantguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Every way the guard can be blind must fail the self-check instead of looking like a clean app. */
class SelfCheckBlindnessTest extends GuardedTestBase {

    private static final String BYPASS_ROLE = "tg_test_bypass";

    @Test
    @DisplayName("BYPASSRLS rolü (superuser değil): self-check FAIL verir")
    void bypassRlsRoleFailsSelfCheck() throws SQLException {
        assertSelfCheckFailsAs("NOSUPERUSER BYPASSRLS", "superuser=false, bypassrls=true");
    }

    @Test
    @DisplayName("NOBYPASSRLS superuser: superuser RLS'i yine de atlar, self-check FAIL verir")
    void superuserWithoutBypassRlsAttributeFailsSelfCheck() throws SQLException {
        // Testcontainers' user has both flags; a superuser bypasses RLS whatever its BYPASSRLS attribute says.
        assertSelfCheckFailsAs("SUPERUSER NOBYPASSRLS", "superuser=true, bypassrls=false");
    }

    private void assertSelfCheckFailsAs(String attributes, String reported) throws SQLException {
        try (Connection owner = ownerConnection(); Statement ddl = owner.createStatement()) {
            ddl.execute("CREATE ROLE " + BYPASS_ROLE + " NOLOGIN " + attributes);
            try {
                DataSource bypassing = new GuardedDataSource(driverDataSource(), guard, BYPASS_ROLE);

                assertThatThrownBy(() -> SelfCheck.verify(bypassing, guard))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("bypasses row level security")
                        .hasMessageContaining(reported);
            } finally {
                ddl.execute("DROP ROLE " + BYPASS_ROLE);
            }
        }
    }

    @Test
    @DisplayName("Proxy yolda değil: RLS çalışır ama notice'ler toplanmaz, self-check FAIL verir")
    void unobservedConnectionFailsSelfCheckEvenAfterOtherObservations() {
        // Something else was observed already; only a notice from the probe table may count.
        guard.runAs(TENANT_B, () -> jdbc.queryForObject("SELECT count(*) FROM invoice", Long.class));
        assertThat(guard.observations()).isNotEmpty();

        DataSource unobserved = new DelegatingDataSource(driverDataSource()) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                try (Statement setup = connection.createStatement()) {
                    setup.execute("SET ROLE " + ObserverInstaller.ROLE + "; SET client_min_messages = notice");
                }
                return connection;
            }
        };

        assertThatThrownBy(() -> SelfCheck.verify(unobserved, guard))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("probe query produced no observer notice");
    }

    @Test
    @DisplayName("Doğru kurulum: self-check geçer")
    void observedRoleWithPolicyPassesSelfCheck() throws SQLException {
        SelfCheck.verify(dataSource, guard);

        assertThat(guard.observations())
                .anySatisfy(o -> assertThat(o.tenantsByTable()).containsKey(ObserverInstaller.PROBE_TABLE));
    }

    private static DataSource driverDataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
