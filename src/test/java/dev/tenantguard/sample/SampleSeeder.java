package dev.tenantguard.sample;

import dev.tenantguard.Extremes;
import dev.tenantguard.TenantSeeder;
import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two invoices with two lines each per tenant. The victim (A) gets extreme amounts so that totals,
 * MAX and MIN change visibly once its data exists. Must run with the guard inactive (the scanner
 * guarantees it), so the inserts go in as the owner.
 */
public final class SampleSeeder implements TenantSeeder {

    public static final String VICTIM = "A";

    private final JdbcTemplate jdbc;

    public SampleSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void seed(String tenant) {
        for (int i = 1; i <= 2; i++) {
            BigDecimal amount = VICTIM.equals(tenant)
                    ? Extremes.amounts().get(i - 1)
                    : BigDecimal.valueOf(100L * i);
            Long id = jdbc.queryForObject(
                    "INSERT INTO invoice (tenant_id, number, amount) VALUES (?, ?, ?) RETURNING id",
                    Long.class, tenant, tenant + "-" + i, amount);
            jdbc.update("INSERT INTO invoice_line (tenant_id, invoice_id, description) VALUES (?, ?, ?), (?, ?, ?)",
                    tenant, id, "line 1", tenant, id, "line 2");
        }
    }
}
