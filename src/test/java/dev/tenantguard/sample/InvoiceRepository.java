package dev.tenantguard.sample;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    /** The bug: a report total computed across every tenant. */
    @Query("select coalesce(sum(i.amount), 0) from Invoice i")
    BigDecimal totalAmountOfAllTenants();

    List<Invoice> findByTenantId(String tenantId);

    Optional<Invoice> findByIdAndTenantId(Long id, String tenantId);
}
