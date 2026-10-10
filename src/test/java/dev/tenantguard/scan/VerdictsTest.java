package dev.tenantguard.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tenantguard.DifferentialProbe;
import dev.tenantguard.Finding;
import dev.tenantguard.scan.HttpCaller.Response;
import dev.tenantguard.scan.ScanResult.Verdict;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Classification branches the sample app does not reach (it has no SUSPECT or 401 endpoint). */
class VerdictsTest {

    private static final Endpoint LIST = Endpoint.of("/lines", "Test#lines");
    private static final Endpoint BY_ID = Endpoint.of("/lines/{id}", "Test#line");
    private static final Finding FOREIGN_READ = new Finding(Finding.Rule.ACTOR_MISMATCH,
            "select l.id from invoice_line l join invoice i on i.id = l.invoice_id where i.tenant_id = ?",
            "invoice_line", Set.of("A"));
    private static final Finding MULTI = new Finding(Finding.Rule.MULTI_TENANT_STATEMENT, "select 1", "invoice",
            Set.of("A", "B"));

    private static Response ok(Object body) {
        return new Response(200, body, null);
    }

    private static DifferentialProbe.Result diff(Set<String> changed, List<Finding> findings) {
        return new DifferentialProbe.Result("/lines", DifferentialProbe.Verdict.CLEAN, changed, Set.of(), findings);
    }

    @Test
    @DisplayName("Liste değişmedi ama observer A'nın satırını gördü: SUSPECT, yalnız ACTOR_MISMATCH tutulur")
    void unchangedListingWithForeignReadIsSuspect() {
        ScanResult result = Verdicts.listing(LIST, List.of(ok("x"), ok("x")), ok("x"),
                diff(Set.of(), List.of(FOREIGN_READ, MULTI)), "A", "B");

        assertThat(result.verdict()).isEqualTo(Verdict.SUSPECT);
        assertThat(result.observerFindings()).containsExactly(FOREIGN_READ);
        assertThat(result.note()).startsWith("response unchanged, but observer: invoice_line rows of tenant A read by select");
    }

    @Test
    @DisplayName("Yalnız status değişti (404 -> 200): LEAK")
    void statusChangeAloneIsLeak() {
        ScanResult result = Verdicts.listing(LIST, List.of(new Response(404, "", null), new Response(404, "", null)),
                ok(""), diff(Set.of(), List.of()), "A", "B");

        assertThat(result.verdict()).isEqualTo(Verdict.LEAK);
        assertThat(result.note()).contains("status 404 -> 200");
    }

    @Test
    @DisplayName("ID endpoint 404 ama observer A'nın satırını gördü: SUSPECT")
    void refusedIdWithForeignReadIsSuspect() {
        ScanResult result = Verdicts.byId(BY_ID, "/lines/7", new Response(404, "", null), List.of(FOREIGN_READ), "A", "B");

        assertThat(result.verdict()).isEqualTo(Verdict.SUSPECT);
        assertThat(result.note()).startsWith("404 for tenant A's /lines/7, but observer:");
    }

    @Test
    @DisplayName("401: saldırgan hiç doğrulanmadı, CLEAN değil SKIPPED; hepsi SKIPPED ise assertNoLeaks düşer")
    void unauthenticatedIsSkippedNotClean() {
        Response unauthorized = new Response(401, "", null);
        ScanResult list = Verdicts.listing(LIST, List.of(unauthorized, unauthorized), unauthorized,
                diff(Set.of(), List.of()), "A", "B");
        ScanResult byId = Verdicts.byId(BY_ID, "/lines/7", unauthorized, List.of(), "A", "B");

        assertThat(list.verdict()).isEqualTo(Verdict.SKIPPED);
        assertThat(byId.verdict()).isEqualTo(Verdict.SKIPPED);
        assertThat(byId.note()).contains("401", "TenantAuthenticator");
        assertThatThrownBy(new ScanReport("A", "B", List.of(list, byId))::assertNoLeaks)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("No endpoint was verified");
    }

    @Test
    @DisplayName("Uzun SQL ve çok sayıda değişen path kısaltılır")
    void notesAreAbbreviated() {
        Finding longSql = new Finding(Finding.Rule.ACTOR_MISMATCH, "select " + "x, ".repeat(100) + "%s from t",
                "t", Set.of("A"));
        ScanResult result = Verdicts.listing(LIST, List.of(ok("x"), ok("x")), ok("y"),
                diff(Set.of("$.a", "$.b", "$.c", "$.d", "$.e"), List.of(longSql)), "A", "B");

        assertThat(result.note()).contains("$.a, $.b, $.c (+2 more)").contains("...");
        assertThat(result.note().length()).isLessThan(250);
    }
}
