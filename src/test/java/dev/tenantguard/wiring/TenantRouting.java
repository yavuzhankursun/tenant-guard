package dev.tenantguard.wiring;

import dev.tenantguard.TenantSeeder;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Database-per-tenant routing as apps write it: the lookup key is the request's tenant, and a request
 * without one falls back to the default target. That fallback is the planted bug: tenant A's
 * database is the default, so B's request without a tenant context reads A's rows.
 */
class TenantRouting extends AbstractRoutingDataSource {

    static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    static final String A = WiringDb.create("wiring_route_a");
    static final String B = WiringDb.create("wiring_route_b");

    static {
        WiringDb.admin("wiring_route_a", WiringDb.INVOICE_DDL);
        WiringDb.admin("wiring_route_b", WiringDb.INVOICE_DDL);
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return CURRENT.get();
    }

    static <T> T as(String tenant, Supplier<T> action) {
        CURRENT.set(tenant);
        try {
            return action.get();
        } finally {
            CURRENT.remove();
        }
    }

    /** Each tenant's rows go to its own database; each database holds one tenant. */
    static TenantSeeder seeder(JdbcTemplate jdbc) {
        return tenant -> as(tenant, () -> {
            jdbc.execute("TRUNCATE invoice RESTART IDENTITY");
            for (int i = 1; i <= 2; i++) {
                jdbc.update("INSERT INTO invoice (tenant_id, number, amount) VALUES (?, ?, ?)",
                        tenant, tenant + "-" + i, 100 * i);
            }
            return null;
        });
    }
}
