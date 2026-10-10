package dev.tenantguard.spring;

import dev.tenantguard.GuardedDataSource;
import dev.tenantguard.ObserverInstaller;
import dev.tenantguard.TenantGuard;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Test-only wiring: one {@link TenantGuard} and every {@link DataSource} bean guarded by a
 * {@link GuardedDataSource}. Nothing else in the application changes; while the guard is inactive
 * the wrapper resets the role, so Flyway and seeders run with the app's own credentials.
 * Imported by {@code @TenantIsolationTest}; never put it on a production component scan path.
 *
 * <p>Opt-in: {@code tenantguard.scheduling=off} stops the app's scheduled jobs (see
 * {@link SchedulingOff}).
 */
@Configuration(proxyBeanMethods = false)
public class TenantGuardConfiguration {

    /** Set to {@code off} to hand every {@code @Scheduled} job to a {@link NoOpTaskScheduler}. */
    public static final String SCHEDULING_PROPERTY = "tenantguard.scheduling";

    @Bean
    TenantGuard tenantGuard() {
        return new TenantGuard();
    }

    /**
     * Static: a post-processor must not force its declaring configuration to initialize early. Spring's
     * DataSource proxies keep their type and get the guard under them, on the pool (see
     * {@link SpringDataSources} for why the order matters); a bean that already reaches a guard, e.g. a
     * proxy over a pool bean, is left alone so no row is observed twice.
     */
    @Bean
    static BeanPostProcessor tenantGuardDataSourceWrapper(ObjectProvider<TenantGuard> guard) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource) {
                    return SpringDataSources.guard(dataSource,
                            target -> new GuardedDataSource(target, guard.getObject(), ObserverInstaller.ROLE));
                }
                return bean;
            }
        };
    }

    /**
     * Scheduled jobs run under the guard's process-wide actor, so a job that reads every tenant's rows
     * during a test is reported as the test tenant's leak. Two layers, because apps pick their
     * scheduler in two ways: the {@code @Primary} bean wins the by-type lookup (and stops Spring
     * Boot from creating its own), the last-ordered configurer overrides a scheduler an app's own
     * {@code SchedulingConfigurer} set. A scheduler the app injects by name and uses directly
     * still runs its tasks.
     */
    @Configuration(proxyBeanMethods = false)
    @Conditional(SchedulingOff.class)
    static class SchedulingOffConfiguration {

        @Bean
        @Primary
        NoOpTaskScheduler tenantGuardNoOpTaskScheduler() {
            return new NoOpTaskScheduler();
        }

        @Bean
        SchedulingConfigurer tenantGuardSchedulingConfigurer(NoOpTaskScheduler noOp) {
            return new LastConfigurer(noOp);
        }
    }

    /** Ordered last: {@code ScheduledAnnotationBeanPostProcessor} applies configurers in order. */
    private record LastConfigurer(NoOpTaskScheduler noOp) implements SchedulingConfigurer, Ordered {

        @Override
        public void configureTasks(ScheduledTaskRegistrar registrar) {
            registrar.setScheduler(noOp);
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }

    /**
     * Matches {@code off} and {@code false}: YAML 1.1 reads an unquoted {@code off} as boolean false.
     * A plain {@link Condition} rather than {@code @ConditionalOnProperty}: the library does not
     * depend on Spring Boot's autoconfigure module.
     */
    static final class SchedulingOff implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String value = context.getEnvironment().getProperty(SCHEDULING_PROPERTY, "").strip().toLowerCase(Locale.ROOT);
            return value.equals("off") || value.equals("false");
        }
    }
}
