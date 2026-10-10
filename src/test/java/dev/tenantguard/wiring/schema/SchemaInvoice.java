package dev.tenantguard.wiring.schema;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** Schema-per-tenant: the schema is the tenant, so the table has no tenant column at all. */
@Entity
@Table(name = "invoice")
public class SchemaInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String number;

    @Column(nullable = false)
    private BigDecimal amount;

    protected SchemaInvoice() {
    }
}
