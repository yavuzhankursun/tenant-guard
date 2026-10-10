package dev.tenantguard.sample;

import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {

    public record ReadOnlyTotal(BigDecimal total, String transactionReadOnly) {
    }

    private final InvoiceRepository invoices;
    private final JdbcTemplate jdbc;

    public ReportService(InvoiceRepository invoices, JdbcTemplate jdbc) {
        this.invoices = invoices;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public ReadOnlyTotal leakyTotalReadOnly() {
        BigDecimal total = invoices.totalAmountOfAllTenants();
        // Same connection as the JPA query: proves the transaction really is READ ONLY on the server.
        String readOnly = jdbc.queryForObject("SHOW transaction_read_only", String.class);
        return new ReadOnlyTotal(total, readOnly);
    }

    @Transactional
    public void leakyTotalThenFail() {
        invoices.totalAmountOfAllTenants();
        throw new IllegalStateException("simulated failure after the leaky read");
    }
}
