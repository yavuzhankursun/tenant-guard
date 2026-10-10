package dev.tenantguard.wiring;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import dev.tenantguard.GuardedDataSource;
import dev.tenantguard.TenantGuard;
import dev.tenantguard.TenantSeeder;
import dev.tenantguard.junit.ObserverSetup;
import dev.tenantguard.junit.TenantIsolationTest;
import java.math.BigDecimal;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The app exposes a {@link LazyConnectionDataSourceProxy} over its pool as the {@code dataSource}
 * bean, with a second pool for read-only transactions (a replica in production; the same database
 * here). The guard goes under the proxy, onto both pools: the bean keeps its type, a connection that
 * never runs a statement still never borrows a physical one, and read-only transactions still go
 * to the replica pool and are observed there.
 */
@SpringBootTest(classes = LazyConnectionProxyWiringTest.App.class)
@TenantIsolationTest(tenantColumn = "tenant_id", tables = "invoice")
class LazyConnectionProxyWiringTest {

    static final String URL = WiringDb.create("wiring_lazy");
    static final String REPLICA = "wiring-replica";

    @JdbcApp
    static class App {

        @Bean
        DataSource dataSource() {
            LazyConnectionDataSourceProxy lazy = new LazyConnectionDataSourceProxy(WiringDb.pool(URL));
            HikariDataSource replica = WiringDb.pool(URL);
            replica.addDataSourceProperty("ApplicationName", REPLICA);
            lazy.setReadOnlyDataSource(replica);
            return lazy;
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
    PlatformTransactionManager transactionManager;

    @Test
    void theBeanKeepsItsTypeAndTheGuardSitsOnThePool(ObserverSetup setup) throws Exception {
        assertThat(dataSource).isInstanceOf(LazyConnectionDataSourceProxy.class);
        assertThat(((LazyConnectionDataSourceProxy) dataSource).getTargetDataSource())
                .isInstanceOf(GuardedDataSource.class);
        assertThat(dataSource.isWrapperFor(GuardedDataSource.class)).isTrue();
        assertThat(setup.warnings()).isEmpty();
    }

    @Test
    void aLeakyReadIsObservedOnce(TenantGuard guard) {
        BigDecimal total = guard.callAs("B", () -> jdbc.queryForObject("SELECT sum(amount) FROM invoice", BigDecimal.class));

        assertThat(total).isEqualByComparingTo("600");
        WiringDb.assertLeakOfA(guard);
    }

    @Test
    void aLeakyReadInAReadOnlyTransactionGoesToTheReplicaPoolAndIsObserved(TenantGuard guard) {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        String application = guard.callAs("B", () -> readOnly.execute(status -> {
            jdbc.queryForObject("SELECT sum(amount) FROM invoice", BigDecimal.class);
            return jdbc.queryForObject("SELECT current_setting('application_name')", String.class);
        }));

        assertThat(application).as("read-only routing still works").isEqualTo(REPLICA);
        WiringDb.assertLeakOfA(guard);
    }

    @Test
    void aConnectionThatRunsNoStatementStaysLazy() throws Exception {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        try (Connection unused = dataSource.getConnection()) {
            assertThat(unused).isNotNull();
            assertThat(pool.getHikariPoolMXBean().getActiveConnections())
                    .as("the guard's SET ROLE runs when the proxy fetches the target, not on getConnection")
                    .isZero();
        }
    }
}
