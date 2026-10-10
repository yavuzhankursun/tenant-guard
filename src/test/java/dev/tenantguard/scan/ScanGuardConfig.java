package dev.tenantguard.scan;

import dev.tenantguard.GuardedDataSource;
import dev.tenantguard.ObserverInstaller;
import dev.tenantguard.TenantGuard;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Same wiring as {@code dev.tenantguard.GuardTestConfig}, which is package-private and therefore
 * not importable from here: wrap the app's DataSource bean, nothing else.
 */
@TestConfiguration(proxyBeanMethods = false)
class ScanGuardConfig {

    @Bean
    TenantGuard tenantGuard() {
        return new TenantGuard();
    }

    @Bean
    static BeanPostProcessor guardDataSource(ObjectProvider<TenantGuard> guard) {
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
