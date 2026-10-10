package dev.tenantguard.wiring;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import dev.tenantguard.GuardedDataSource;
import dev.tenantguard.ObserverInstaller;
import dev.tenantguard.TenantGuard;
import dev.tenantguard.TenantSeeder;
import dev.tenantguard.junit.ObserverSetup;
import dev.tenantguard.junit.TenantIsolationTest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The app exposes a {@link TransactionAwareDataSourceProxy} as the {@code dataSource} bean, so legacy
 * code calling {@code dataSource.getConnection()} joins the Spring transaction. The order matters:
 * the transaction manager unwraps the proxy and binds its target, and the proxy looks connections up
 * by that same target. A guard <em>above</em> the proxy becomes the binding key instead, and raw
 * connections silently leave the transaction. So the guard has to sit under the proxy.
 */
@SpringBootTest(classes = TransactionAwareProxyWiringTest.App.class)
@TenantIsolationTest(tenantColumn = "tenant_id", tables = "invoice")
class TransactionAwareProxyWiringTest {

    static final String URL = WiringDb.create("wiring_tx_aware");

    @JdbcApp
    static class App {

        @Bean
        DataSource dataSource() {
            return new TransactionAwareDataSourceProxy(WiringDb.pool(URL));
        }

        @Bean
        TenantSeeder seeder(JdbcTemplate jdbc) {
            return WiringDb.seeder(jdbc);
        }
    }

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    @Test
    void theBeanKeepsItsTypeAndTheGuardSitsOnThePool(ObserverSetup setup) {
        assertThat(dataSource).isInstanceOf(TransactionAwareDataSourceProxy.class);
        assertThat(((TransactionAwareDataSourceProxy) dataSource).getTargetDataSource())
                .isInstanceOf(GuardedDataSource.class);
        assertThat(setup.warnings()).isEmpty();
    }

    @Test
    void aRawConnectionInsideATransactionIsTheTransactionsConnectionAndIsObserved(TenantGuard guard) {
        guard.runAs("B", () -> transactions.executeWithoutResult(status -> {
            int transactionPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            assertThat(rawPidAndLeak(dataSource)).as("raw getConnection() joins the transaction")
                    .isEqualTo(transactionPid);
        }));

        WiringDb.assertLeakOfA(guard);
    }

    /** Why the order matters, built by hand: the guard above the proxy breaks the proxy's contract. */
    @Test
    void aGuardAboveTheProxyLetsRawConnectionsEscapeTheTransaction() {
        TenantGuard inactive = new TenantGuard();
        try (HikariDataSource pool = WiringDb.pool(URL)) {
            DataSource guardAbove = new GuardedDataSource(new TransactionAwareDataSourceProxy(pool), inactive,
                    ObserverInstaller.ROLE);
            JdbcTemplate template = new JdbcTemplate(guardAbove);
            new TransactionTemplate(new JdbcTransactionManager(guardAbove)).executeWithoutResult(status -> {
                int transactionPid = template.queryForObject("SELECT pg_backend_pid()", Integer.class);
                assertThat(rawPidAndLeak(guardAbove)).isNotEqualTo(transactionPid);
            });
        }
    }

    private static int rawPidAndLeak(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT pg_backend_pid(), (SELECT sum(amount) FROM invoice)")) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
