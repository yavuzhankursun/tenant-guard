package dev.tenantguard.sample;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Three planted bugs and two correctly scoped endpoints for the scanner to tell apart. */
@RestController
public class InvoiceController {

    public record InvoiceDto(Long id, String number, BigDecimal amount) {

        static InvoiceDto of(Invoice invoice) {
            return new InvoiceDto(invoice.getId(), invoice.getNumber(), invoice.getAmount());
        }
    }

    /** generatedAt differs on every call: the probe must mask it as noise, not report it. */
    public record TotalReport(BigDecimal total, String generatedAt) {
    }

    private final InvoiceRepository invoices;

    public InvoiceController(InvoiceRepository invoices) {
        this.invoices = invoices;
    }

    /** BUG: every tenant's invoices. */
    @GetMapping("/invoices")
    public List<InvoiceDto> all() {
        return invoices.findAll().stream().map(InvoiceDto::of).toList();
    }

    /** BUG: IDOR, no tenant check. */
    @GetMapping("/invoices/{id}")
    public ResponseEntity<InvoiceDto> byId(@PathVariable Long id) {
        return ResponseEntity.of(invoices.findById(id).map(InvoiceDto::of));
    }

    /** BUG: total across every tenant. */
    @GetMapping("/reports/total")
    public TotalReport total() {
        return new TotalReport(invoices.totalAmountOfAllTenants(), Instant.now().toString());
    }

    @GetMapping("/my/invoices")
    public List<InvoiceDto> mine() {
        return invoices.findByTenantId(TenantContext.current()).stream().map(InvoiceDto::of).toList();
    }

    @GetMapping("/my/invoices/{id}")
    public ResponseEntity<InvoiceDto> mineById(@PathVariable Long id) {
        return ResponseEntity.of(invoices.findByIdAndTenantId(id, TenantContext.current()).map(InvoiceDto::of));
    }
}
