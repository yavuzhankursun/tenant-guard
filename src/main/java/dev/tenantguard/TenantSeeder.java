package dev.tenantguard;

/**
 * Inserts one tenant's data. Written by the user: only they know the schema. Called with the guard
 * inactive, so the rows are written as the owner and never observed. Seed the tenant that will be
 * leaked (A) with extreme values (largest and smallest amounts, oldest and newest dates) so that
 * MAX/MIN/ORDER BY ... LIMIT queries change too; see the differential probe.
 */
@FunctionalInterface
public interface TenantSeeder {

    void seed(String tenant);
}
