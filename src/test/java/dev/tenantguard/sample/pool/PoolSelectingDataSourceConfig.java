package dev.tenantguard.sample.pool;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Lets the whole suite run on another pool: {@code mvn test -Dtenantguard.pool=c3p0}. Picked up by
 * {@code SampleApp}'s component scan, so every Spring context gets it. Plain Hikari is Boot's own
 * auto-configured pool, so then this backs off and nothing changes. The bean is a plain
 * {@link DataSource}: {@code TenantGuardConfiguration}'s post-processor must wrap it like any other.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(PoolSelectingDataSourceConfig.NotBootDefault.class)
public class PoolSelectingDataSourceConfig {

    /** Same size as Boot's Hikari in application.properties. */
    private static final int MAX_SIZE = 4;

    @Bean
    DataSource dataSource(Environment env) {
        return selected(env).create(new TestPool.Settings(
                env.getRequiredProperty("spring.datasource.url"),
                env.getRequiredProperty("spring.datasource.username"),
                env.getRequiredProperty("spring.datasource.password"),
                MAX_SIZE,
                cacheEnabled(env)));
    }

    public static TestPool selected(Environment env) {
        return TestPool.of(env.getProperty(TestPool.PROPERTY));
    }

    public static boolean cacheEnabled(Environment env) {
        return env.getProperty(TestPool.CACHE_PROPERTY, Boolean.class, false);
    }

    static final class NotBootDefault implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            return selected(env) != TestPool.HIKARI || cacheEnabled(env);
        }
    }
}
