package dev.tenantguard.wiring;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import dev.tenantguard.GuardedDataSource;
import dev.tenantguard.TenantGuard;
import dev.tenantguard.TenantSeeder;
import dev.tenantguard.junit.ObserverSetup;
import dev.tenantguard.junit.TenantIsolationTest;
import dev.tenantguard.spring.TenantGuardConfiguration;
import java.math.BigDecimal;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanNotOfRequiredTypeException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Field case: the app built its pool itself in a {@code @Configuration}, from the raw
 * {@code SPRING_DATASOURCE_*} keys, so Boot's {@code DataSourceAutoConfiguration} backed off. The
 * post-processor wraps any {@code DataSource} bean, whoever built it; Flyway migrates through the
 * same (inactive) wrapper as the owner.
 */
@SpringBootTest(classes = DataSourceBuilderWiringTest.App.class)
@TenantIsolationTest(tenantColumn = "tenant_id", tables = "invoice")
class DataSourceBuilderWiringTest {

    static final String URL = WiringDb.create("wiring_builder");

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        registry.add("SPRING_DATASOURCE_URL", () -> URL);
        registry.add("SPRING_DATASOURCE_USERNAME", () -> WiringDb.ADMIN);
        registry.add("SPRING_DATASOURCE_PASSWORD", () -> WiringDb.ADMIN_PASSWORD);
    }

    /** The app's own config class, as in the field run: reads the environment keys, not spring.datasource.*. */
    @Configuration(proxyBeanMethods = false)
    static class AppDataSourceConfig {

        @Bean
        DataSource dataSource(Environment env) {
            return DataSourceBuilder.create()
                    .type(HikariDataSource.class)
                    .url(env.getRequiredProperty("SPRING_DATASOURCE_URL"))
                    .username(env.getRequiredProperty("SPRING_DATASOURCE_USERNAME"))
                    .password(env.getRequiredProperty("SPRING_DATASOURCE_PASSWORD"))
                    .build();
        }
    }

    @JdbcApp
    @Import(AppDataSourceConfig.class)
    static class App {

        @Bean
        TenantSeeder seeder(JdbcTemplate jdbc) {
            return WiringDb.seeder(jdbc);
        }
    }

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void handBuiltPoolIsGuardedAndALeakyReadIsObserved(TenantGuard guard, ObserverSetup setup)
            throws Exception {
        assertThat(dataSource.isWrapperFor(GuardedDataSource.class)).isTrue();
        assertThat(dataSource.unwrap(HikariDataSource.class).getJdbcUrl()).isEqualTo(URL);
        assertThat(setup.installedTables()).containsExactly("invoice");
        assertThat(setup.warnings()).isEmpty();

        BigDecimal total = guard.callAs("B", () -> jdbc.queryForObject("SELECT sum(amount) FROM invoice", BigDecimal.class));

        assertThat(total).isEqualByComparingTo("600");
        WiringDb.assertLeakOfA(guard);
    }

    /** A pool bean declared with its concrete type, and another bean that injects it by that type. */
    @Configuration(proxyBeanMethods = false)
    static class ConcretelyTypedPool {

        @Bean
        HikariDataSource dataSource() {
            HikariDataSource pool = new HikariDataSource();
            pool.setJdbcUrl(URL);
            pool.setUsername(WiringDb.ADMIN);
            pool.setPassword(WiringDb.ADMIN_PASSWORD);
            return pool;
        }

        @Bean
        String poolName(HikariDataSource pool) {
            return String.valueOf(pool.getPoolName());
        }
    }

    /**
     * The wrapper is a plain {@code DataSource}: injecting the pool by its concrete type fails at
     * startup, naming the wrapper. Loud, never blind; the fix on the app side is to inject
     * {@code DataSource} and {@code unwrap(HikariDataSource.class)}.
     */
    @Test
    void injectingThePoolByItsConcreteTypeFailsAtStartupNamingTheWrapper() {
        new ApplicationContextRunner()
                .withUserConfiguration(TenantGuardConfiguration.class, ConcretelyTypedPool.class)
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(BeanNotOfRequiredTypeException.class)
                        .hasStackTraceContaining("Bean named 'dataSource' is expected to be of type "
                                + "'com.zaxxer.hikari.HikariDataSource' but was actually of type "
                                + "'dev.tenantguard.GuardedDataSource'"));
    }
}
