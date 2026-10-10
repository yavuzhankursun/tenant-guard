package example.invoices

import dev.tenantguard.Finding.Rule
import dev.tenantguard.TenantGuard
import dev.tenantguard.TenantSeeder
import dev.tenantguard.junit.ObserverSetup
import dev.tenantguard.junit.TenantIsolationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.math.BigDecimal

/**
 * Tenant A is the victim, B the attacker (the annotation's defaults). Flyway creates `invoice`;
 * before each test the extension seeds both tenants, installs the observer on every table with a
 * `tenant_id` column and arms the guard.
 */
@SpringBootTest
@TenantIsolationTest(tenantColumn = "tenant_id")
class InvoiceIsolationTest {

    @TestConfiguration(proxyBeanMethods = false)
    class Config {

        @Bean
        @ServiceConnection
        fun postgres(): PostgreSQLContainer<*> = PostgreSQLContainer<Nothing>("postgres:16-alpine")

        /** Called with the guard inactive, as the table owner; the victim (A) first. */
        @Bean
        fun seeder(jdbc: JdbcTemplate) = TenantSeeder { tenant ->
            if (tenant == "A") {
                jdbc.execute("TRUNCATE invoice RESTART IDENTITY")
            }
            listOf("INV-1" to "100.00", "INV-2" to "200.00").forEach { (number, amount) ->
                jdbc.update(
                    "INSERT INTO invoice (tenant_id, number, amount) VALUES (?, ?, ?)",
                    tenant, number, BigDecimal(amount),
                )
            }
        }
    }

    @Autowired
    lateinit var invoices: InvoiceRepository

    @Test
    fun `the observer covers the invoice table`(setup: ObserverSetup) {
        assertThat(setup.installedTables()).containsExactly("invoice")
        assertThat(setup.skippedTables()).isEmpty()
    }

    @Test
    fun `a tenant-scoped lookup reads only the acting tenant`(guard: TenantGuard) {
        val invoice = guard.callAs("B") { invoices.findByTenantIdAndNumber("B", "INV-1") }

        assertThat(invoice?.tenantId).isEqualTo("B")
        assertThat(guard.findings()).isEmpty()
    }

    @Test
    fun `the number search without a tenant filter leaks A to B`(guard: TenantGuard) {
        val found = guard.callAs("B") { invoices.searchByNumberPrefix("INV-") }

        // The observer saw B's statement read A's rows...
        assertThat(guard.findings(Rule.ACTOR_MISMATCH)).singleElement().satisfies({ finding ->
            assertThat(finding.table()).isEqualTo("invoice")
            assertThat(finding.tenants()).containsExactly("A")
        })
        assertThat(guard.findings(Rule.MULTI_TENANT_STATEMENT)).hasSize(1)
        // ...and here they also reached the caller.
        assertThat(found.map { it.tenantId }).containsOnly("A", "B")
    }
}
