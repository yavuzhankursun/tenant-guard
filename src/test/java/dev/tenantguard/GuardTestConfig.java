package dev.tenantguard;

import dev.tenantguard.spring.TenantGuardConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Guard wiring without the JUnit extension's lifecycle, for tests that arm the guard themselves.
 * Tests extending {@link GuardedTestBase} get the same wiring through {@code @TenantIsolationTest}.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(TenantGuardConfiguration.class)
class GuardTestConfig {
}
