package dev.tenantguard.spring;

import dev.tenantguard.GuardedDataSource;
import dev.tenantguard.ObserverInstaller;
import dev.tenantguard.TenantGuard;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Test-only wiring: one {@link TenantGuard} and every {@link DataSource} bean wrapped in a
 * {@link GuardedDataSource}. Nothing else in the application changes; while the guard is inactive
 * the wrapper resets the role, so Flyway and seeders run with the app's own credentials.
 * Imported by {@code @TenantIsolationTest}; never put it on a production component scan path.
 */
@Configuration(proxyBeanMethods = false)
public class TenantGuardConfiguration {

    @Bean
    TenantGuard tenantGuard() {
        return new TenantGuard();
    }

    /** Static: a post-processor must not force its declaring configuration to initialize early. */
    @Bean
    static BeanPostProcessor tenantGuardDataSourceWrapper(ObjectProvider<TenantGuard> guard) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource && !(bean instanceof GuardedDataSource)) {
                    return new GuardedDataSource(dataSource, guard.getObject(), ObserverInstaller.ROLE);
                }
                return bean;
            }
        };
    }
}
