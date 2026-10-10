package dev.tenantguard.sample;

import dev.tenantguard.TenantSeeder;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two invoices with two lines each per tenant. The victim (A) gets amounts 1000x larger so that
 * totals and MAX-style queries change visibly once its data exists. Must run with the guard
 * inactive (the scanner guarantees it), so the inserts go in as the owner.
 */
public final class SampleSeeder implements TenantSeeder {

    public static final String VICTIM = "A";

    private final JdbcTemplate jdbc;

    public SampleSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void seed(String tenant) {
        int scale = VICTIM.equals(tenant) ? 1_000 : 1;
        for (int i = 1; i <= 2; i++) {
            Long id = jdbc.queryForObject(
                    "INSERT INTO invoice (tenant_id, number, amount) VALUES (?, ?, ?) RETURNING id",
                    Long.class, tenant, tenant + "-" + i, 100 * i * scale);
            jdbc.update("INSERT INTO invoice_line (tenant_id, invoice_id, description) VALUES (?, ?, ?), (?, ?, ?)",
                    tenant, id, "line 1", tenant, id, "line 2");
        }
    }
}
