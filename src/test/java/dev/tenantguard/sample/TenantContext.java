package dev.tenantguard.sample;

/** The request's tenant, bound by {@link TenantFilter} from the {@code X-Tenant} header. */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static String current() {
        String tenant = CURRENT.get();
        if (tenant == null) {
            throw new IllegalStateException("No tenant bound to this request");
        }
        return tenant;
    }

    static void set(String tenant) {
        CURRENT.set(tenant);
    }

    static void clear() {
        CURRENT.remove();
    }
}
