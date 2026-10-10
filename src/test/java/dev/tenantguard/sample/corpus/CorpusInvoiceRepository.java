package dev.tenantguard.sample.corpus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

/** Every method without a tenant argument is a planted leak; the two at the bottom are correct. */
public interface CorpusInvoiceRepository extends JpaRepository<CorpusInvoice, Long>,
        JpaSpecificationExecutor<CorpusInvoice> {

    /** Interface projection that never selects {@code tenant_id}. */
    interface NameOnly {
        String getName();
    }

    @Query("select c from CorpusInvoice c order by c.id")
    List<CorpusInvoice> jpqlAll();

    /** Filters by a non-tenant field; every tenant's open invoices match. */
    List<CorpusInvoice> findByStatusOrderById(String status);

    @Query(value = "SELECT * FROM corpus_invoice ORDER BY id", nativeQuery = true)
    List<CorpusInvoice> nativeAll();

    @Query(value = "SELECT coalesce(sum(amount), 0) FROM corpus_invoice", nativeQuery = true)
    BigDecimal nativeSum();

    @Query("select c.name as name from CorpusInvoice c order by c.id")
    List<NameOnly> allNames();

    @Query(value = "SELECT name FROM corpus_invoice ORDER BY amount DESC LIMIT 1", nativeQuery = true)
    String nativeTopByAmount();

    /** {@code lower()} is not LEAKPROOF: Postgres must run the RLS policy before it. */
    @Query(value = "SELECT EXISTS(SELECT 1 FROM corpus_invoice WHERE lower(name) = lower(:name))", nativeQuery = true)
    boolean nameTakenIgnoringCase(String name);

    /** {@code text = text} is LEAKPROOF: Postgres may run it before the RLS policy. */
    @Query(value = "SELECT EXISTS(SELECT 1 FROM corpus_invoice WHERE name = :name)", nativeQuery = true)
    boolean nameTaken(String name);

    @Query("select max(c.amount) from CorpusInvoice c")
    BigDecimal maxAmount();

    @Query("select min(c.amount) from CorpusInvoice c")
    BigDecimal minAmount();

    @Query("select c from CorpusInvoice c where c.id in :ids order by c.id")
    List<CorpusInvoice> byIds(List<Long> ids);

    @Query(value = """
            WITH per_status AS (
                SELECT status, count(*) AS invoices, sum(amount) AS total FROM corpus_invoice GROUP BY status
            )
            SELECT status || ':' || invoices || ':' || total FROM per_status ORDER BY status""", nativeQuery = true)
    List<String> nativeCteSummary();

    @Query(value = "SELECT coalesce(sum(amount), 0) FROM corpus_invoice_view", nativeQuery = true)
    BigDecimal viewSum();

    @Query(value = "SELECT coalesce(sum(amount), 0) FROM corpus_invoice_invoker_view", nativeQuery = true)
    BigDecimal invokerViewSum();

    /** Correct. */
    List<CorpusInvoice> findByTenantIdOrderById(String tenantId);

    /** Correct. */
    Optional<CorpusInvoice> findByIdAndTenantId(Long id, String tenantId);
}
