package dev.tenantguard.sample;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * By-id endpoints reproducing a field run's false LEAKs: called with the victim's id, a correctly
 * tenant-filtered query finds nothing and the handler still answers 2xx ({@code []}, {@code 0},
 * {@code 0.00}, {@code null}, {@code {}}, {@code false}). Two unfiltered controls must stay LEAK, so
 * the empty-response rule cannot be over-broad. Off by default: the other scan tests count the
 * sample app's endpoints exactly.
 */
@RestController
@ConditionalOnProperty(name = FieldCaseController.ENABLED, havingValue = "true")
public class FieldCaseController {

    public static final String ENABLED = "tenantguard.sample.field-cases";

    private final JdbcTemplate jdbc;

    public FieldCaseController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 200 []: child list filtered by tenant. */
    @GetMapping("/field/invoices/{id}/lines")
    public List<String> lines(@PathVariable long id) {
        return jdbc.queryForList("SELECT description FROM invoice_line WHERE invoice_id = ? AND tenant_id = ? ORDER BY id",
                String.class, id, TenantContext.current());
    }

    /** 200 0: count filtered by tenant. */
    @GetMapping("/field/invoices/{id}/line-count")
    public long lineCount(@PathVariable long id) {
        return jdbc.queryForObject("SELECT count(*) FROM invoice_line WHERE invoice_id = ? AND tenant_id = ?",
                Long.class, id, TenantContext.current());
    }

    /**
     * 200 0.00, and the observer sees the victim's row: {@code lower()} is not leakproof, so Postgres
     * applies it after the observer policy. Correct result, but the row was read: SUSPECT.
     */
    @GetMapping("/field/invoices/{id}/paid")
    public BigDecimal paid(@PathVariable long id) {
        return jdbc.queryForObject(
                "SELECT coalesce(sum(amount), 0.00) FROM invoice WHERE id = ? AND lower(tenant_id) = lower(?)",
                BigDecimal.class, id, TenantContext.current());
    }

    /** 200 null: a lookup that serializes "not mine" as JSON null. */
    @GetMapping("/field/invoices/{id}/number")
    public JsonNode number(@PathVariable long id) {
        List<String> numbers = jdbc.queryForList("SELECT number FROM invoice WHERE id = ? AND tenant_id = ?",
                String.class, id, TenantContext.current());
        return numbers.isEmpty() ? NullNode.getInstance() : TextNode.valueOf(numbers.getFirst());
    }

    /** 200 {}: a summary map that stays empty when the invoice is not the caller's. */
    @GetMapping("/field/invoices/{id}/summary")
    public Map<String, Object> summary(@PathVariable long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT number, amount FROM invoice WHERE id = ? AND tenant_id = ?", id, TenantContext.current());
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    /** 200 false. */
    @GetMapping("/field/invoices/{id}/exists")
    public boolean exists(@PathVariable long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM invoice WHERE id = ? AND tenant_id = ?)",
                Boolean.class, id, TenantContext.current()));
    }

    /** Control, BUG: no tenant check, returns the victim's invoice. Must stay LEAK. */
    @GetMapping("/field/invoices/{id}/details")
    public Map<String, Object> details(@PathVariable long id) {
        return jdbc.queryForMap("SELECT id, number, amount FROM invoice WHERE id = ?", id);
    }

    /** Control, BUG: no tenant check, counts the victim's lines (2). A non-zero scalar must stay LEAK. */
    @GetMapping("/field/invoices/{id}/all-line-count")
    public long allLineCount(@PathVariable long id) {
        return jdbc.queryForObject("SELECT count(*) FROM invoice_line WHERE invoice_id = ?", Long.class, id);
    }
}
