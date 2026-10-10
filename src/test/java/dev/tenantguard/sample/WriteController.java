package dev.tenantguard.sample;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Write endpoints for the write scan: three planted cross-tenant writes (update by id, delete by id,
 * create trusting a {@code tenantId} from the body), their correct counterparts, and a PATCH whose
 * body type ({@link JsonNode}) no generator can fill. The update reproduces a field finding, a
 * {@code PUT /stock/{productId}/{branchId}/quantity} whose repository method lacked the tenant
 * predicate. Off by default: the other scan tests count the sample app's endpoints exactly.
 */
@RestController
@ConditionalOnProperty(name = WriteController.ENABLED, havingValue = "true")
public class WriteController {

    public static final String ENABLED = "tenantguard.sample.writes";

    public record InvoiceUpdate(String number, BigDecimal amount) {
    }

    /** {@code tenantId} is what a careless client-facing DTO carries; only the leaky POST honours it. */
    public record NewInvoice(String tenantId, String number, BigDecimal amount) {
    }

    private final JdbcTemplate jdbc;

    public WriteController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** BUG: updates by id without the tenant predicate. */
    @PutMapping("/w/invoices/{id}")
    public ResponseEntity<Void> update(@PathVariable long id, @RequestBody InvoiceUpdate body) {
        return status(jdbc.update("UPDATE invoice SET number = ?, amount = ? WHERE id = ?",
                body.number(), body.amount(), id));
    }

    @PutMapping("/w/my/invoices/{id}")
    public ResponseEntity<Void> updateMine(@PathVariable long id, @RequestBody InvoiceUpdate body) {
        return status(jdbc.update("UPDATE invoice SET number = ?, amount = ? WHERE id = ? AND tenant_id = ?",
                body.number(), body.amount(), id, TenantContext.current()));
    }

    /** BUG: patches by id without the tenant predicate; the untyped body defeats body generation. */
    @PatchMapping("/w/invoices/{id}")
    public ResponseEntity<Void> patch(@PathVariable long id, @RequestBody JsonNode body) {
        return status(jdbc.update("UPDATE invoice SET number = ? WHERE id = ?", body.path("number").asText("?"), id));
    }

    /** BUG: deletes the invoice and its lines by id, whoever owns them. */
    @DeleteMapping("/w/invoices/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable long id) {
        jdbc.update("DELETE FROM invoice_line WHERE invoice_id = ?", id);
        return status(jdbc.update("DELETE FROM invoice WHERE id = ?", id));
    }

    @DeleteMapping("/w/my/invoices/{id}")
    @Transactional
    public ResponseEntity<Void> deleteMine(@PathVariable long id) {
        String tenant = TenantContext.current();
        jdbc.update("DELETE FROM invoice_line WHERE invoice_id = ? AND tenant_id = ?", id, tenant);
        return status(jdbc.update("DELETE FROM invoice WHERE id = ? AND tenant_id = ?", id, tenant));
    }

    /** BUG: tenant spoofing, the body's tenantId wins over the caller's tenant. */
    @PostMapping("/w/invoices")
    public ResponseEntity<Map<String, Long>> create(@RequestBody NewInvoice body) {
        String tenant = body.tenantId() != null ? body.tenantId() : TenantContext.current();
        return created(insert(tenant, body));
    }

    /** Correct: the tenant comes from the request context; a tenantId in the body is ignored. */
    @PostMapping("/w/my/invoices")
    public ResponseEntity<Map<String, Long>> createMine(@RequestBody NewInvoice body) {
        return created(insert(TenantContext.current(), body));
    }

    private long insert(String tenant, NewInvoice body) {
        return jdbc.queryForObject("INSERT INTO invoice (tenant_id, number, amount) VALUES (?, ?, ?) RETURNING id",
                Long.class, tenant, body.number(), body.amount());
    }

    private static ResponseEntity<Void> status(int rows) {
        return rows == 0 ? ResponseEntity.notFound().build() : ResponseEntity.noContent().build();
    }

    private static ResponseEntity<Map<String, Long>> created(long id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }
}
