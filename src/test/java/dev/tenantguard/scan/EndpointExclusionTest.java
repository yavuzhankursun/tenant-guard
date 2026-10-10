package dev.tenantguard.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tenantguard.TenantGuard;
import dev.tenantguard.scan.ScanResult.Verdict;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Field run: the attacker token carried platform permissions, so TENANT_MANAGE-only endpoints
 * legitimately listed every tenant (4 false LEAKs). Users exclude them; the report says why.
 */
class EndpointExclusionTest {

    @RestController
    static class AdminController {

        final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

        private String hit(String path) {
            calls.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            return "[\"every tenant\"]";
        }

        @GetMapping("/api/v1/tenants")
        String tenants() {
            return hit("/api/v1/tenants");
        }

        @GetMapping("/api/v1/tenants/{id}")
        String tenant(@PathVariable String id) {
            return hit("/api/v1/tenants/{id}");
        }

        @GetMapping("/api/v1/admin/audit")
        String audit() {
            return hit("/api/v1/admin/audit");
        }

        @GetMapping("/api/v1/me")
        String me() {
            return "me";
        }
    }

    private final TenantGuard guard = new TenantGuard();
    private final AdminController controller = new AdminController();
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

    private EndpointScanner.Builder scanner(String... templates) {
        return EndpointScanner.builder(mockMvc, guard)
                .endpoints(List.of(templates).stream().map(t -> Endpoint.of(t, "AdminController")).toList())
                .seeder(tenant -> { })
                .authenticator(tenant -> request -> request)
                .resourceIds((template, variable, tenant) -> {
                    throw new AssertionError("excluded endpoints must not resolve ids: " + template);
                });
    }

    @Test
    @DisplayName("exclude(ant pattern): eşleşen endpoint EXCLUDED, hiç çağrılmaz, not kuralı söyler")
    void antPatternExcludesWithoutCalling() {
        ScanReport report = scanner("/api/v1/tenants", "/api/v1/tenants/{id}", "/api/v1/me")
                .exclude("/api/v1/tenants/**")
                .build()
                .scan();

        assertThat(report.result("/api/v1/tenants").verdict()).isEqualTo(Verdict.EXCLUDED);
        assertThat(report.result("/api/v1/tenants/{id}").verdict()).isEqualTo(Verdict.EXCLUDED);
        assertThat(report.result("/api/v1/tenants/{id}").note()).isEqualTo("excluded by rule /api/v1/tenants/**");
        assertThat(report.result("/api/v1/tenants/{id}").status()).isZero();
        assertThat(report.result("/api/v1/me").verdict()).isEqualTo(Verdict.CLEAN);
        assertThat(controller.calls).isEmpty();
    }

    @Test
    @DisplayName("excludeIf(predicate) ve excludeIf(sebep, predicate); ilk eşleşen kural yazılır")
    void predicatesExcludeWithReason() {
        ScanReport report = scanner("/api/v1/tenants", "/api/v1/admin/audit", "/api/v1/me")
                .excludeIf("TENANT_MANAGE only", e -> e.pathTemplate().startsWith("/api/v1/tenants"))
                .excludeIf(e -> e.pathTemplate().contains("/admin/"))
                .build()
                .scan();

        assertThat(report.result("/api/v1/tenants").note()).isEqualTo("excluded by rule TENANT_MANAGE only");
        assertThat(report.result("/api/v1/admin/audit").note()).isEqualTo("excluded by rule excludeIf predicate");
        assertThat(report.withVerdict(Verdict.EXCLUDED)).hasSize(2);
        assertThat(controller.calls).isEmpty();
    }

    @Test
    @DisplayName("EXCLUDED assertNoLeaks'i düşürmez ama tam tabloda sebebiyle listelenir")
    void excludedNeverFailsButIsListed() {
        ScanReport report = scanner("/api/v1/tenants", "/api/v1/me").exclude("/api/v1/tenants").build().scan();

        assertThatCode(report::assertNoLeaks).doesNotThrowAnyException();
        assertThatCode(report::assertNoLeaksOrSuspects).doesNotThrowAnyException();
        assertThat(report.toString()).contains("EXCLUDED", "GET /api/v1/tenants", "excluded by rule /api/v1/tenants");
    }

    @Test
    @DisplayName("Her şey hariç tutulduysa hiçbir şey doğrulanmadı: assertNoLeaks düşer")
    void excludingEverythingIsNotAPass() {
        ScanReport report = scanner("/api/v1/tenants", "/api/v1/tenants/{id}").exclude("/**").build().scan();

        assertThatThrownBy(report::assertNoLeaks)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("No endpoint was verified")
                .hasMessageContaining("2 EXCLUDED");
    }

    @Test
    @DisplayName("Geçersiz pattern ve null reddedilir")
    void invalidRulesAreRejected() {
        assertThatThrownBy(() -> scanner().exclude("api/v1/tenants/**")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scanner().exclude(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scanner().exclude((String) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> scanner().excludeIf(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> scanner().excludeIf(" ", e -> true)).isInstanceOf(IllegalArgumentException.class);
    }
}
