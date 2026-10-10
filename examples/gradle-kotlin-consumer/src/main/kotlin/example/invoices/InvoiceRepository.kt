package example.invoices

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface InvoiceRepository : JpaRepository<Invoice, Long> {

    /** Scoped: the tenant is part of the lookup. */
    fun findByTenantIdAndNumber(tenantId: String, number: String): Invoice?

    /**
     * The bug this example is about: a "search by number" that forgot the tenant. Every tenant
     * numbers its invoices the same way, so the search returns other tenants' invoices too.
     */
    @Query("select i from Invoice i where i.number like concat(:prefix, '%') order by i.number")
    fun searchByNumberPrefix(prefix: String): List<Invoice>
}
