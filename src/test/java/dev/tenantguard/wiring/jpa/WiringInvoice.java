package dev.tenantguard.wiring.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** The invoice table as the JPA wiring tests map it; {@code ddl-auto} creates it from here. */
@Entity
@Table(name = "invoice")
public class WiringInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String number;

    @Column(nullable = false)
    private BigDecimal amount;

    protected WiringInvoice() {
    }

    public String getTenantId() {
        return tenantId;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
