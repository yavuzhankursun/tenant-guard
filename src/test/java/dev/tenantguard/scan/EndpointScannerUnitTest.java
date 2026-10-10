package dev.tenantguard.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tenantguard.TenantGuard;
import dev.tenantguard.scan.ScanResult.Verdict;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Scanner mechanics without a database: standalone MockMvc, fresh guard, no-op seeder. */
class EndpointScannerUnitTest {

    @RestController
    static class OddController {

        @GetMapping("/boom")
        Map<String, Object> boom() {
            throw new IllegalStateException("handler blew up");
        }

        @GetMapping("/fail")
        ResponseEntity<String> fail() {
            return ResponseEntity.status(503).body("down");
        }

        @GetMapping("/text")
        String text() {
            return "plain";
        }

        @GetMapping("/things/{id}")
        String thing(@PathVariable String id) {
            return "thing " + id;
        }
    }

    private final TenantGuard guard = new TenantGuard();
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new OddController()).build();

    private static Endpoint endpoint(String template) {
        return Endpoint.of(template, "OddController");
    }

    private ScanReport scan(ResourceIds ids, String... templates) {
        return EndpointScanner.builder(mockMvc, guard)
                .endpoints(List.of(templates).stream().map(EndpointScannerUnitTest::endpoint).toList())
                .seeder(tenant -> { })
                .authenticator(tenant -> request -> request)
                .resourceIds(ids)
                .build()
                .scan();
    }

    @Test
    @DisplayName("Handler exception'ı ve 5xx: ERROR, tarama devam eder")
    void exceptionsAndServerErrorsAreErrors() {
        ScanReport report = scan(ResourceIds.builder().build(), "/boom", "/fail", "/text");

        assertThat(report.result("/boom").verdict()).isEqualTo(Verdict.ERROR);
        assertThat(report.result("/boom").note()).contains("handler blew up");
        assertThat(report.result("/fail").verdict()).isEqualTo(Verdict.ERROR);
        assertThat(report.result("/fail").status()).isEqualTo(503);
        assertThat(report.result("/text").verdict()).isEqualTo(Verdict.CLEAN);
        assertThatThrownBy(report::assertNoLeaks).isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("ID değeri path segmenti olarak encode edilir")
    void resolvedIdIsEncoded() {
        ResourceIds ids = ResourceIds.builder().id("/things/{id}", "id", tenant -> tenant + " 1/2").build();

        ScanResult result = scan(ids, "/things/{id}").result("/things/{id}");

        assertThat(result.resolvedPath()).isEqualTo("/things/A%201%2F2");
        assertThat(result.verdict()).isEqualTo(Verdict.LEAK);
    }

    @Test
    @DisplayName("Override null dönerse veya fırlatırsa: SKIPPED, sebebiyle")
    void failingOverrideIsSkipped() {
        ResourceIds ids = ResourceIds.builder().id("/things/{id}", "id", tenant -> {
            throw new IllegalStateException("lookup broke");
        }).build();

        ScanResult result = scan(ids, "/things/{id}").result("/things/{id}");

        assertThat(result.verdict()).isEqualTo(Verdict.SKIPPED);
        assertThat(result.note()).contains("lookup broke");
        assertThat(scan(ResourceIds.builder().id("/things/{id}", "id", tenant -> null).build(), "/things/{id}")
                .result("/things/{id}").verdict()).isEqualTo(Verdict.SKIPPED);
    }

    @Test
    @DisplayName("Guard'ın başlangıç durumu geri yüklenir; eksik builder alanı açık hata verir")
    void restoresGuardStateAndValidatesBuilder() {
        guard.deactivate();
        scan(ResourceIds.builder().build(), "/text");
        assertThat(guard.isActive()).isFalse();

        assertThatThrownBy(() -> EndpointScanner.builder(mockMvc, guard).endpoints(List.of()).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seeder");
        assertThatThrownBy(() -> EndpointScanner.builder(mockMvc, guard).tenants("A", "A"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Set.of(Verdict.values())).hasSize(6);
    }
}
