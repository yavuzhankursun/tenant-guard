package example.invoices

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal

@Entity
@Table(name = "invoice")
class Invoice(
    @Column(name = "tenant_id", nullable = false, length = 64)
    val tenantId: String,

    @Column(nullable = false, length = 64)
    val number: String,

    @Column(nullable = false, precision = 12, scale = 2)
    val amount: BigDecimal,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
)
