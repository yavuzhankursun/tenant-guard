package dev.tenantguard.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tenantguard.sample.SampleSeeder;
import dev.tenantguard.sample.WriteController;
import dev.tenantguard.scan.ScanResult.Verdict;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Cross-tenant writes against the sample app's gated write controller, judged by the victim's data. */
@TestPropertySource(properties = WriteController.ENABLED + "=true")
class WriteScanTest extends ScanTestBase {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ApplicationContext context;

    private EndpointScanner.Builder scanner(List<Endpoint> endpoints) {
        return EndpointScanner.builder(mockMvc, guard)
                .endpoints(endpoints)
                .seeder(new SampleSeeder(jdbc))
                .authenticator(EndpointScanTest.X_TENANT)
                .resourceIds(ResourceIds.builder().fallback(new TableGuessingResourceIds(dataSource, "tenant_id")).build())
                .includeWrites(dataSource, "tenant_id");
    }

    private List<Endpoint> writes() {
        return EndpointDiscovery.discoverWrites(context);
    }

    private static ScanReport run(EndpointScanner.Builder builder) {
        ScanReport report = builder.build().scan();
        System.out.printf("[write-scan]%n%s%n", report);
        return report;
    }

    @Test
    @DisplayName("PUT /w/invoices/{id}: tenant kontrolsüz update, A'nın satırı değişti -> LEAK, tablo ve değişiklik notta")
    void leakyUpdateIsLeakByData() {
        ScanResult put = run(scanner(writes())).result("PUT /w/invoices/{id}");

        assertThat(put.verdict()).isEqualTo(Verdict.LEAK);
        assertThat(put.status()).isEqualTo(204);
        assertThat(put.resolvedPath()).matches("/w/invoices/\\d+");
        assertThat(put.note()).startsWith("tenant A's data changed: invoice: 1 row updated");
    }

    @Test
    @DisplayName("DELETE /w/invoices/{id}: A'nın faturası ve iki satırı silindi -> LEAK, iki tablo da notta")
    void leakyDeleteIsLeakAcrossTables() {
        ScanResult delete = run(scanner(writes())).result("DELETE /w/invoices/{id}");

        assertThat(delete.verdict()).isEqualTo(Verdict.LEAK);
        assertThat(delete.note()).contains("invoice: 1 row deleted", "invoice_line: 2 rows deleted");
    }

    @Test
    @DisplayName("Tenant kontrollü PUT ve DELETE: 404, A'nın verisi aynı -> CLEAN")
    void scopedWritesAreClean() {
        ScanReport report = run(scanner(writes()));

        for (String label : List.of("PUT /w/my/invoices/{id}", "DELETE /w/my/invoices/{id}")) {
            ScanResult result = report.result(label);
            assertThat(result.verdict()).as(label).isEqualTo(Verdict.CLEAN);
            assertThat(result.status()).as(label).isEqualTo(404);
            assertThat(result.note()).as(label).isEqualTo("404, tenant A's data unchanged");
        }
    }

    @Test
    @DisplayName("Spoof probe: body'deki tenantId=A'ya güvenen POST A adına satır yaratır -> LEAK; context'ten alan POST CLEAN")
    void spoofedTenantOnCreateIsLeak() {
        ScanReport report = run(scanner(writes()).spoofTenantField());

        ScanResult spoofable = report.result("POST /w/invoices");
        assertThat(spoofable.verdict()).isEqualTo(Verdict.LEAK);
        assertThat(spoofable.status()).isEqualTo(201);
        assertThat(spoofable.note()).startsWith("spoofed tenantId=A: tenant A's data changed: invoice: 1 row created with tenant A");
        ScanResult scoped = report.result("POST /w/my/invoices");
        assertThat(scoped.verdict()).isEqualTo(Verdict.CLEAN);
        assertThat(scoped.status()).isEqualTo(201);
    }

    @Test
    @DisplayName("Spoof kapalı (varsayılan): gövdeye tenant alanı konmaz, POST B adına yaratır -> CLEAN")
    void withoutSpoofTheBodyCarriesNoTenant() {
        ScanResult post = run(scanner(writes())).result("POST /w/invoices");

        assertThat(post.verdict()).isEqualTo(Verdict.CLEAN);
        assertThat(post.note()).isEqualTo("201, tenant A's data unchanged");
    }

    @Test
    @DisplayName("JsonNode gövdeli PATCH: gövde üretilemez -> SKIPPED, sebep ve .body(...) önerisi; kullanıcı gövdesiyle LEAK")
    void unknownBodyIsSkippedUntilTheUserSuppliesOne() {
        ScanResult skipped = run(scanner(writes())).result("PATCH /w/invoices/{id}");
        assertThat(skipped.verdict()).isEqualTo(Verdict.SKIPPED);
        assertThat(skipped.status()).isZero();
        assertThat(skipped.note()).contains("JsonNode", ".body(\"PATCH /w/invoices/{id}\", tenant -> json)");

        ScanResult supplied = run(scanner(writes())
                .body("PATCH /w/invoices/{id}", tenant -> "{\"number\": \"patched-by-" + tenant + "\"}"))
                .result("PATCH /w/invoices/{id}");
        assertThat(supplied.verdict()).isEqualTo(Verdict.LEAK);
        assertThat(supplied.note()).contains("invoice: 1 row updated");
    }

    @Test
    @DisplayName("assertNoLeaks: tam olarak üç sızan yazma endpoint'i, doğru olanlar listede yok")
    void assertNoLeaksListsExactlyTheThreeLeakyWrites() {
        ScanReport report = run(scanner(writes()).spoofTenantField());

        assertThatThrownBy(report::assertNoLeaks)
                .isInstanceOf(AssertionError.class)
                .satisfies(error -> {
                    List<String> leakRows = error.getMessage().lines().filter(l -> l.strip().startsWith("LEAK")).toList();
                    assertThat(leakRows).hasSize(3)
                            .anyMatch(l -> l.contains("PUT /w/invoices/{id} "))
                            .anyMatch(l -> l.contains("DELETE /w/invoices/{id} "))
                            .anyMatch(l -> l.contains("POST /w/invoices "));
                    assertThat(error.getMessage()).doesNotContain("/w/my/");
                });
    }

    @Test
    @DisplayName("İzolasyon: her yazma öncesi taze veri, DELETE'ler en sonda; tarama sonunda iki tenant'ın seed'i eksiksiz")
    void eachWriteRunsOnFreshDataAndDeletesRunLast() {
        List<String> methods = new ArrayList<>();
        TenantAuthenticator recording = tenant -> request -> {
            methods.add(request.getMethod());
            return EndpointScanTest.X_TENANT.forTenant(tenant).postProcessRequest(request);
        };

        ScanReport report = run(scanner(writes().reversed()).authenticator(recording).spoofTenantField());

        // PATCH is skipped (no body); both POSTs carry tenantId, so each runs twice: plain and spoofed.
        assertThat(methods).hasSize(8).endsWith("DELETE", "DELETE");
        assertThat(methods.subList(0, 6)).doesNotContain("DELETE");
        assertThat(report.withVerdict(Verdict.LEAK)).hasSize(3);
        assertThat(jdbc.queryForList("SELECT number FROM invoice ORDER BY number", String.class))
                .containsExactly("A-1", "A-2", "B-1", "B-2");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoice_line", Integer.class)).isEqualTo(8);
        assertThat(guard.isActive()).isTrue();
    }

    @Test
    @DisplayName("resetWith: kullanıcı reset'i owner olarak çalışır; yalnız veriyi değiştiren yazmadan sonra yeniden seed")
    void customResetRunsOnlyAfterWritesThatChangedData() {
        List<Boolean> resets = new ArrayList<>();
        Runnable reset = () -> {
            resets.add(guard.isActive());
            jdbc.execute("TRUNCATE invoice_line, invoice");
        };

        ScanReport report = run(scanner(writes()).resetWith(reset));

        // Initial reset, then before POST /w/my (POST /w wrote B's row), PUT /w (POST /w/my wrote),
        // PUT /w/my (PUT /w wrote) and DELETE /w/my (DELETE /w wrote). The 404s and the skipped PATCH reuse the seed.
        assertThat(resets).hasSize(5).containsOnly(false);
        assertThat(report.withVerdict(Verdict.LEAK)).extracting(r -> r.endpoint().label())
                .containsExactly("PUT /w/invoices/{id}", "DELETE /w/invoices/{id}");
    }

    @Test
    @DisplayName("GET ve yazma aynı raporda: GET sonuçları değişmez, ikisi de assertNoLeaks'e girer")
    void readsAndWritesShareOneReport() {
        List<Endpoint> all = Stream.concat(EndpointDiscovery.discover(context).stream(), writes().stream()).toList();

        ScanReport report = run(scanner(all));

        assertThat(report.result("/invoices/{id}").verdict()).isEqualTo(Verdict.LEAK);
        assertThat(report.result("/my/invoices/{id}").verdict()).isEqualTo(Verdict.CLEAN);
        assertThat(report.result("GET /invoices").verdict()).isEqualTo(Verdict.LEAK);
        assertThat(report.withVerdict(Verdict.LEAK)).extracting(r -> r.endpoint().label())
                .containsExactlyInAnyOrder("GET /invoices", "GET /invoices/{id}", "GET /reports/total",
                        "PUT /w/invoices/{id}", "DELETE /w/invoices/{id}");
        assertThat(report.results()).extracting(r -> r.endpoint().label())
                .containsExactlyElementsOf(all.stream().map(Endpoint::label).toList());
    }

    @Test
    @DisplayName("Yazma endpoint'i listede ama includeWrites yok: build açık hatayla durur, veri sessizce değişmez")
    void writesNeedExplicitOptIn() {
        EndpointScanner.Builder builder = EndpointScanner.builder(mockMvc, guard)
                .endpoints(writes())
                .seeder(new SampleSeeder(jdbc))
                .authenticator(EndpointScanTest.X_TENANT);

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("includeWrites")
                .hasMessageContaining("PUT /w/invoices/{id}");
    }
}
