package dev.tenantguard.wiring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tenantguard.GuardedDataSource;
import dev.tenantguard.TenantGuard;
import dev.tenantguard.TenantSeeder;
import dev.tenantguard.junit.ObserverSetup;
import dev.tenantguard.junit.TenantIsolationTest;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

/**
 * The pool is a bean of its own and the proxies are beans over it: a {@code @Primary}
 * {@link LazyConnectionDataSourceProxy} and a {@link TransactionAwareDataSourceProxy}. The pool bean
 * is guarded; the proxies already reach a guard and must not get a second one, or every row would
 * be reported twice. All three beans lead to the same guard, so none is reported as unobserved.
 */
@SpringBootTest(classes = ProxyOverPoolBeanWiringTest.App.class)
@TenantIsolationTest(tenantColumn = "tenant_id", tables = "invoice")
class ProxyOverPoolBeanWiringTest {

    static final String URL = WiringDb.create("wiring_proxy_over_pool");

    @JdbcApp
    static class App {

        @Bean
        DataSource pool() {
            return WiringDb.pool(URL);
        }

        @Bean
        @Primary
        DataSource dataSource(@Qualifier("pool") DataSource pool) {
            return new LazyConnectionDataSourceProxy(pool);
        }

        @Bean
        DataSource transactionAware(@Qualifier("pool") DataSource pool) {
            return new TransactionAwareDataSourceProxy(pool);
        }

        @Bean
        TenantSeeder seeder(JdbcTemplate jdbc) {
            return WiringDb.seeder(jdbc);
        }
    }

    @Autowired
    @Qualifier("pool")
    DataSource pool;

    @Autowired
    DataSource dataSource;

    @Autowired
    @Qualifier("transactionAware")
    DataSource transactionAware;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void onlyThePoolIsWrappedAndEveryBeanLeadsToIt(ObserverSetup setup) throws Exception {
        assertThat(pool).isInstanceOf(GuardedDataSource.class);
        assertThat(dataSource).isInstanceOf(LazyConnectionDataSourceProxy.class);
        assertThat(transactionAware).isInstanceOf(TransactionAwareDataSourceProxy.class);
        assertThat(dataSource.unwrap(GuardedDataSource.class)).isSameAs(pool);
        assertThat(transactionAware.unwrap(GuardedDataSource.class)).isSameAs(pool);
        assertThat(setup.warnings()).isEmpty();
    }

    @Test
    void aLeakyReadThroughThePrimaryProxyIsObservedOnce(TenantGuard guard) {
        BigDecimal total = guard.callAs("B", () -> jdbc.queryForObject("SELECT sum(amount) FROM invoice", BigDecimal.class));

        assertThat(total).isEqualByComparingTo("600");
        WiringDb.assertLeakOfA(guard);
    }

    @Test
    void aLeakyReadThroughTheTransactionAwareProxyIsObservedOnce(TenantGuard guard) {
        guard.runAs("B", () -> {
            try (Connection connection = transactionAware.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT sum(amount) FROM invoice")) {
                rs.next();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        WiringDb.assertLeakOfA(guard);
    }
}
