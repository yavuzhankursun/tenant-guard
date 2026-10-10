package dev.tenantguard.sample.corpus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hibernate.SessionFactory;
import org.hibernate.StatelessSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Access paths that need more than a repository method. All leaky unless the name says otherwise. */
@Service
public class CorpusQueries {

    private final CorpusInvoiceRepository invoices;
    private final JdbcTemplate jdbc;
    private final EntityManagerFactory entityManagerFactory;

    @PersistenceContext
    private EntityManager entityManager;

    public CorpusQueries(CorpusInvoiceRepository invoices, JdbcTemplate jdbc, EntityManagerFactory entityManagerFactory) {
        this.invoices = invoices;
        this.jdbc = jdbc;
        this.entityManagerFactory = entityManagerFactory;
    }

    public List<Map<String, Object>> jdbcList() {
        return jdbc.queryForList("SELECT id, name, amount FROM corpus_invoice ORDER BY id");
    }

    public Long jdbcCount() {
        return jdbc.queryForObject("SELECT count(*) FROM corpus_invoice", Long.class);
    }

    @Transactional(readOnly = true)
    public List<InvoiceView> criteriaAll() {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<CorpusInvoice> query = cb.createQuery(CorpusInvoice.class);
        Root<CorpusInvoice> root = query.from(CorpusInvoice.class);
        query.select(root).orderBy(cb.asc(root.get("id")));
        return entityManager.createQuery(query).getResultList().stream().map(InvoiceView::of).toList();
    }

    public List<InvoiceView> specificationOpen() {
        Specification<CorpusInvoice> open = (root, query, cb) -> cb.equal(root.get("status"), "open");
        return invoices.findAll(open, Sort.by("id")).stream().map(InvoiceView::of).toList();
    }

    /** IDOR with the check done in memory: the foreign row is loaded, then hidden as a 404. */
    public String loadThenCheck(long id, String tenant) {
        Optional<CorpusInvoice> visible = invoices.findById(id).filter(i -> i.getTenantId().equals(tenant));
        return visible.map(i -> InvoiceView.of(i).toString()).orElse("404");
    }

    public Map<String, Object> largestFirstPage() {
        Page<CorpusInvoice> page = invoices.findAll(PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "amount")));
        return Map.of("content", page.getContent().stream().map(InvoiceView::of).toList(),
                "totalElements", page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public List<InvoiceView> readOnlyAll() {
        return invoices.jpqlAll().stream().map(InvoiceView::of).toList();
    }

    public BigDecimal functionTotal() {
        return jdbc.queryForObject("SELECT corpus_total()", BigDecimal.class);
    }

    public BigDecimal definerFunctionTotal() {
        return jdbc.queryForObject("SELECT corpus_total_definer()", BigDecimal.class);
    }

    /** Hibernate's StatelessSession borrows its own connection, outside the Spring transaction. */
    public List<InvoiceView> statelessAll() {
        try (StatelessSession session = entityManagerFactory.unwrap(SessionFactory.class).openStatelessSession()) {
            return session.createSelectionQuery("from CorpusInvoice order by id", CorpusInvoice.class)
                    .getResultList().stream().map(InvoiceView::of).toList();
        }
    }

    /** {@code getResultStream()} scrolls the JDBC ResultSet; the stream needs the open transaction. */
    @Transactional(readOnly = true)
    public List<String> nativeStreamNames() {
        try (var names = entityManager.createNativeQuery("SELECT name FROM corpus_invoice ORDER BY id", String.class)
                .getResultStream()) {
            return names.map(String.class::cast).toList();
        }
    }
}
