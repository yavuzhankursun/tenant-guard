package dev.tenantguard.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tenantguard.Finding;
import dev.tenantguard.TenantSeeder;
import dev.tenantguard.sample.SampleSeeder;
import dev.tenantguard.sample.TenantFilter;
import dev.tenantguard.scan.ScanResult.Verdict;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

/** The scanner against the sample app: three planted leaks, two correctly scoped endpoints. */
class EndpointScanTest extends ScanTestBase {

    static final TenantAuthenticator X_TENANT = tenant -> request -> {
        request.addHeader(TenantFilter.HEADER, tenant);
        return request;
    };

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ApplicationContext context;

    private EndpointScanner.Builder scanner(TenantSeeder seeder) {
        return EndpointScanner.builder(mockMvc, guard)
                .endpoints(EndpointDiscovery.discover(context))
                .seeder(seeder)
                .authenticator(X_TENANT)
                .resourceIds(ResourceIds.builder()
                        .fallback(new TableGuessingResourceIds(dataSource, "tenant_id"))
                        .build());
    }

    private ScanReport scan() {
        ScanReport report = scanner(new SampleSeeder(jdbc)).build().scan();
        System.out.printf("[scan]%n%s%n", report);
        return report;
    }

    @Test
    @DisplayName("GET /invoices: findAll, B'nin listesi A eklenince değişir -> LEAK (diff)")
    void unfilteredListIsLeakByDiff() {
        ScanResult result = scan().result("/invoices");

        assertThat(result.verdict()).isEqualTo(Verdict.LEAK);
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.changedPaths()).contains("$.size", "$[2].id", "$[3].amount");
        assertThat(result.observerFindings()).extracting(Finding::table).contains("invoice");
    }

    @Test
    @DisplayName("GET /invoices/{id}: A'nın ID'siyle B'ye 200 -> LEAK (IDOR)")
    void idorIsLeakBy2xxOnVictimId() {
        ScanResult result = scan().result("/invoices/{id}");

        assertThat(result.verdict()).isEqualTo(Verdict.LEAK);
        assertThat(result.resolvedPath()).isEqualTo("/invoices/3");
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.note()).contains("tenant A", "observer: invoice rows of tenant A read by select");
    }

    @Test
    @DisplayName("GET /reports/total: tüm tenantların toplamı -> LEAK, yalnız $.total değişir, generatedAt gürültü")
    void crossTenantTotalIsLeakOnTotalOnly() {
        ScanResult result = scan().result("/reports/total");

        assertThat(result.verdict()).isEqualTo(Verdict.LEAK);
        assertThat(result.changedPaths()).containsExactly("$.total");
    }

    @Test
    @DisplayName("GET /my/invoices: tenant filtreli -> CLEAN")
    void scopedListIsClean() {
        ScanResult result = scan().result("/my/invoices");

        assertThat(result.verdict()).isEqualTo(Verdict.CLEAN);
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.observerFindings()).isEmpty();
    }

    @Test
    @DisplayName("GET /my/invoices/{id}: 404 ve CLEAN, SUSPECT değil: id=? ve tenant_id=? leakproof, "
            + "Postgres onları observer politikasından önce uygular, A'nın satırı politikaya hiç ulaşmaz")
    void scopedByIdIsCleanBecauseLeakproofQualsRunBeforeTheObserver() {
        ScanResult result = scan().result("/my/invoices/{id}");

        assertThat(result.status()).isEqualTo(404);
        assertThat(result.resolvedPath()).isEqualTo("/my/invoices/3");
        assertThat(result.verdict()).isEqualTo(Verdict.CLEAN);
        assertThat(result.observerFindings()).isEmpty();
    }

    @Test
    @DisplayName("assertNoLeaks: tam olarak üç sızan endpoint'i tablo halinde listeler")
    void assertNoLeaksListsExactlyTheThreeLeakyEndpoints() {
        ScanReport report = scan();

        assertThatThrownBy(report::assertNoLeaks)
                .isInstanceOf(AssertionError.class)
                .satisfies(error -> {
                    String message = error.getMessage();
                    System.out.printf("[scan] assertNoLeaks message:%n%s%n", message);
                    List<String> leakRows = message.lines().filter(l -> l.strip().startsWith("LEAK")).toList();
                    assertThat(leakRows).hasSize(3);
                    assertThat(leakRows).anyMatch(l -> l.contains("GET /invoices "))
                            .anyMatch(l -> l.contains("GET /invoices/{id} "))
                            .anyMatch(l -> l.contains("GET /reports/total "));
                    assertThat(message).doesNotContain("/my/");
                });
        assertThatThrownBy(report::assertNoLeaksOrSuspects).isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("Seed sırası B sonra A, ikisi de guard kapalıyken; tarama sonrası guard tekrar aktif")
    void seedsAsOwnerAndRestoresTheGuard() {
        List<String> calls = new ArrayList<>();
        TenantSeeder recording = tenant -> {
            calls.add(tenant + ":" + (guard.isActive() ? "active" : "inactive"));
            new SampleSeeder(jdbc).seed(tenant);
        };

        scanner(recording).build().scan();

        assertThat(calls).containsExactly("B:inactive", "A:inactive");
        assertThat(guard.isActive()).isTrue();
    }

    @Test
    @DisplayName("Çözülemeyen path değişkeni: endpoint SKIPPED ve sebebiyle raporlanır, sessizce düşmez")
    void unresolvableIdIsReportedAsSkipped() {
        List<Endpoint> endpoints = Stream.concat(EndpointDiscovery.discover(context).stream(),
                Stream.of(new Endpoint("/widgets/{id}", Set.of("id"), "Fake#widget"))).toList();

        ScanReport report = scanner(new SampleSeeder(jdbc)).endpoints(endpoints).build().scan();

        ScanResult skipped = report.result("/widgets/{id}");
        assertThat(skipped.verdict()).isEqualTo(Verdict.SKIPPED);
        assertThat(skipped.note()).contains("widget");
        assertThat(report.toString()).contains("SKIPPED", "/widgets/{id}");
    }

    @Test
    @DisplayName("Builder override: kullanıcı ID'yi kendisi verir, tablo tahmini devreye girmez")
    void builderOverrideWins() {
        ResourceIds ids = ResourceIds.builder()
                .id("/invoices/{id}", "id", tenant -> jdbc.queryForObject(
                        "SELECT max(id) FROM invoice WHERE tenant_id = ?", Long.class, tenant))
                .fallback(new TableGuessingResourceIds(dataSource, "tenant_id"))
                .build();

        ScanReport report = scanner(new SampleSeeder(jdbc)).resourceIds(ids).build().scan();

        assertThat(report.result("/invoices/{id}").resolvedPath()).isEqualTo("/invoices/4");
        assertThat(report.result("/my/invoices/{id}").resolvedPath()).isEqualTo("/my/invoices/3");
    }
}
