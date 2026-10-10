package dev.tenantguard.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tenantguard.sample.FieldCaseController;
import dev.tenantguard.sample.SampleSeeder;
import dev.tenantguard.scan.ScanResult.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Field run: by-id endpoints answering 2xx with an empty body were 8 of 15 false LEAKs. */
@TestPropertySource(properties = FieldCaseController.ENABLED + "=true")
class EmptyResponseScanTest extends ScanTestBase {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ApplicationContext context;

    ScanReport report;

    @BeforeEach
    void scan() {
        report = EndpointScanner.builder(mockMvc, guard)
                .endpoints(EndpointDiscovery.discover(context).stream()
                        .filter(e -> e.pathTemplate().startsWith("/field/")).toList())
                .seeder(new SampleSeeder(jdbc))
                .authenticator(EndpointScanTest.X_TENANT)
                .resourceIds(ResourceIds.builder().fallback(new TableGuessingResourceIds(dataSource, "tenant_id")).build())
                .build()
                .scan();
        System.out.printf("[scan]%n%s%n", report);
    }

    @Test
    @DisplayName("200 [] / 0 / null / {} / false, tenant filtreli sorgu, observer sessiz -> CLEAN, notla")
    void emptyBodiesFromFilteredQueriesAreClean() {
        assertClean("/field/invoices/{id}/lines", "200 with empty body [] for tenant A's /field/invoices/3/lines");
        assertClean("/field/invoices/{id}/line-count", "200 with empty body 0 for tenant A's");
        assertClean("/field/invoices/{id}/number", "200 with empty body null for tenant A's");
        assertClean("/field/invoices/{id}/summary", "200 with empty body {} for tenant A's");
        assertClean("/field/invoices/{id}/exists", "200 with empty body false for tenant A's");
    }

    @Test
    @DisplayName("200 0.00 ama observer A'nın satırını gördü (lower() leakproof değil) -> SUSPECT")
    void emptyBodyWithForeignReadIsSuspect() {
        ScanResult paid = report.result("/field/invoices/{id}/paid");

        assertThat(paid.verdict()).isEqualTo(Verdict.SUSPECT);
        assertThat(paid.status()).isEqualTo(200);
        assertThat(paid.note()).startsWith("200 with empty body 0.00 for tenant A's /field/invoices/3/paid, but observer:")
                .contains("invoice rows of tenant A");
    }

    @Test
    @DisplayName("Kontroller: A'nın verisi ({..}) ve sıfır olmayan sayı (2) LEAK kalır")
    void nonEmptyBodiesStayLeaks() {
        assertThat(report.result("/field/invoices/{id}/details").verdict()).isEqualTo(Verdict.LEAK);
        assertThat(report.result("/field/invoices/{id}/all-line-count").verdict()).isEqualTo(Verdict.LEAK);
        assertThatThrownBy(report::assertNoLeaks)
                .isInstanceOf(AssertionError.class)
                .satisfies(error -> assertThat(error.getMessage().lines().filter(l -> l.strip().startsWith("LEAK")))
                        .hasSize(2));
    }

    private void assertClean(String template, String notePrefix) {
        ScanResult result = report.result(template);
        assertThat(result.verdict()).as(template).isEqualTo(Verdict.CLEAN);
        assertThat(result.status()).as(template).isEqualTo(200);
        assertThat(result.observerFindings()).as(template).isEmpty();
        assertThat(result.note()).as(template).startsWith(notePrefix);
    }
}
