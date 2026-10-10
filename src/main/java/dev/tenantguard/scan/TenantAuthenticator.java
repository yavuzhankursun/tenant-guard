package dev.tenantguard.scan;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * How a request acts as a tenant: a header, a JWT, a session, {@code SecurityMockMvcRequestPostProcessors.user(...)}.
 * Only the application knows, so the user writes it. Getting it wrong makes every call a 401, which
 * the scanner reports as SKIPPED (not verified) instead of CLEAN.
 */
@FunctionalInterface
public interface TenantAuthenticator {

    RequestPostProcessor forTenant(String tenant);
}
