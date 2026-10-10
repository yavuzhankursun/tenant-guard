package dev.tenantguard.scan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tenantguard.DifferentialProbe;
import dev.tenantguard.Finding;
import dev.tenantguard.scan.HttpCaller.Response;
import dev.tenantguard.scan.ScanResult.Verdict;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The empty-response rule for id-scored endpoints, branch by branch. */
class EmptyResponseVerdictsTest {

    private static final Endpoint BY_ID = Endpoint.of("/lines/{id}", "Test#line");
    private static final Endpoint LIST = Endpoint.of("/lines", "Test#lines");
    private static final Finding FOREIGN_READ = new Finding(Finding.Rule.ACTOR_MISMATCH,
            "select sum(amount) from invoice where id = ? and lower(tenant_id) = lower(?)", "invoice", Set.of("A"));

    static List<Object> emptyBodies() {
        return java.util.Arrays.asList(List.of(), Map.of(), null, "", "  ", 0, 0L, new BigDecimal("0.00"), 0.0d,
                Boolean.FALSE);
    }

    static List<Object> nonEmptyBodies() {
        return List.of(List.of(Map.of()), Map.of("id", 3), "A-1", 2, new BigDecimal("0.01"), Boolean.TRUE, "0");
    }

    private static ScanResult byId(Object body, List<Finding> findings) {
        return Verdicts.byId(BY_ID, "/lines/7", new Response(200, body, null), findings, "A", "B");
    }

    @ParameterizedTest
    @MethodSource("emptyBodies")
    @DisplayName("2xx + boş gövde, observer sessiz: CLEAN, not neden LEAK sayılmadığını söyler")
    void emptyBodyWithoutForeignReadIsClean(Object body) {
        ScanResult result = byId(body, List.of());

        assertThat(result.verdict()).isEqualTo(Verdict.CLEAN);
        assertThat(result.note()).startsWith("200 with empty body ").endsWith(" for tenant A's /lines/7");
    }

    @ParameterizedTest
    @MethodSource("emptyBodies")
    @DisplayName("2xx + boş gövde ama observer A'nın satırını gördü: SUSPECT")
    void emptyBodyWithForeignReadIsSuspect(Object body) {
        ScanResult result = byId(body, List.of(FOREIGN_READ));

        assertThat(result.verdict()).isEqualTo(Verdict.SUSPECT);
        assertThat(result.note()).contains(" for tenant A's /lines/7, but observer: invoice rows of tenant A");
    }

    @ParameterizedTest
    @MethodSource("nonEmptyBodies")
    @DisplayName("2xx + boş olmayan gövde (string \"0\" dahil): LEAK kalır")
    void nonEmptyBodyStaysLeak(Object body) {
        assertThat(byId(body, List.of()).verdict()).isEqualTo(Verdict.LEAK);
    }

    @Test
    @DisplayName("Not, gövdeyi okunur yazar: [] {} null \"\" 0 0.00 false")
    void noteRendersTheBody() {
        assertThat(byId(List.of(), List.of()).note()).isEqualTo("200 with empty body [] for tenant A's /lines/7");
        assertThat(byId(Map.of(), List.of()).note()).contains("body {} for");
        assertThat(byId(null, List.of()).note()).contains("body null for");
        assertThat(byId("", List.of()).note()).contains("body \"\" for");
        assertThat(byId(new BigDecimal("0.00"), List.of()).note()).contains("body 0.00 for");
        assertThat(byId(false, List.of()).note()).contains("body false for");
    }

    @Test
    @DisplayName("Liste/diff endpoint'lerinde değişiklik yok: [] -> [] ama diff değişti dediyse LEAK")
    void listingsAreUnaffected() {
        Response empty = new Response(200, List.of(), null);
        DifferentialProbe.Result changed = new DifferentialProbe.Result("/lines", DifferentialProbe.Verdict.LEAK,
                Set.of("$.size"), Set.of(), List.of());

        ScanResult result = Verdicts.listing(LIST, List.of(empty, empty), empty, changed, "A", "B");

        assertThat(result.verdict()).isEqualTo(Verdict.LEAK);
    }
}
