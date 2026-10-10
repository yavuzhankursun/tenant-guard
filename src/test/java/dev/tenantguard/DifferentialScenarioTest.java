package dev.tenantguard;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tenantguard.DifferentialProbe.Endpoint;
import dev.tenantguard.DifferentialProbe.Result;
import dev.tenantguard.DifferentialProbe.Verdict;
import dev.tenantguard.sample.InvoiceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Scenario 9: before/after diff. Starts with B's data only; A is seeded mid-test with
 * {@link Extremes} amounts, so MAX/MIN/ORDER BY ... LIMIT change when A's rows leak.
 * Repository/JDBC calls stand in for the list and report endpoints the scanner will hit over HTTP.
 */
class DifferentialScenarioTest extends GuardedTestBase {

    @Autowired
    InvoiceRepository invoices;

    @Override
    List<String> initialTenants() {
        return List.of(TENANT_B);
    }

    private List<Endpoint> endpoints() {
        return List.of(
                new Endpoint("leaky-sum-report", () -> Map.of(
                        "total", invoices.totalAmountOfAllTenants(),
                        "generatedAt", Instant.now().toString())),
                new Endpoint("leaky-list", () -> invoices.findAll().stream()
                        .map(i -> Map.of("id", i.getId(), "number", i.getNumber(), "amount", i.getAmount()))
                        .toList()),
                new Endpoint("filtered-list", () -> jdbc.queryForList(
                        "SELECT id, number, amount FROM invoice WHERE tenant_id = 'B' ORDER BY id")),
                new Endpoint("join-filtered", () -> jdbc.queryForList(
                        "SELECT l.id, l.description FROM invoice_line l JOIN invoice i ON i.id = l.invoice_id "
                                + "WHERE i.tenant_id = 'B' ORDER BY l.id")),
                new Endpoint("leaky-max", () -> jdbc.queryForObject("SELECT max(amount) FROM invoice", BigDecimal.class)),
                new Endpoint("leaky-min", () -> jdbc.queryForObject("SELECT min(amount) FROM invoice", BigDecimal.class)),
                new Endpoint("leaky-top1", () -> jdbc.queryForObject(
                        "SELECT number FROM invoice ORDER BY amount DESC LIMIT 1", String.class)),
                // "Is this number free?" checked across all tenants: false before and after A arrives.
                // lower() is not LEAKPROOF, so the policy runs first and sees every row. With a plain
                // `number = 'B-3'` the leakproof texteq runs before the policy and A's rows are never observed.
                new Endpoint("leaky-exists", () -> jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM invoice WHERE lower(number) = 'b-3')", Boolean.class)),
                new Endpoint("exists-any", () -> jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM invoice)", Boolean.class)));
    }

    @Test
    @DisplayName("9a. Önce/sonra farkı, A uç değerlerle: SUM, liste, MAX, MIN, TOP-1 kesin; JOIN şüpheli; filtreli temiz")
    void beforeAfterDiffWithExtremeSeed() {
        DifferentialProbe probe = DifferentialProbe.recordBaseline(guard, TENANT_B, endpoints());
        seedTenantAsOwner(TENANT_A);
        Map<String, Result> results = byName(probe.compare());
        report(results);

        assertThat(results.get("leaky-sum-report").verdict()).isEqualTo(Verdict.LEAK);
        assertThat(results.get("leaky-sum-report").changedPaths()).containsExactly("$.total");
        assertThat(results.get("leaky-sum-report").noisePaths()).containsExactly("$.generatedAt");
        assertThat(results.get("leaky-list").verdict()).isEqualTo(Verdict.LEAK);
        assertThat(results.get("filtered-list").verdict()).isEqualTo(Verdict.CLEAN);
        // Diff is silent on the correctly filtered join, but the observer still flags it.
        assertThat(results.get("join-filtered").verdict()).isEqualTo(Verdict.SUSPECT);
        // With ordinary seed values MAX stayed unchanged (SUSPECT only); A's extremes move all three.
        assertThat(results.get("leaky-max").verdict()).isEqualTo(Verdict.LEAK);
        assertThat(results.get("leaky-min").verdict()).isEqualTo(Verdict.LEAK);
        assertThat(results.get("leaky-top1").verdict()).isEqualTo(Verdict.LEAK);
    }

    @Test
    @DisplayName("9b. EXISTS: boolean cevap değişmiyor, fark kör; tek sinyal gözlemci (SUSPECT)")
    void existsIsBlindToTheDiffOnlyTheObserverSeesIt() {
        DifferentialProbe probe = DifferentialProbe.recordBaseline(guard, TENANT_B, endpoints());
        seedTenantAsOwner(TENANT_A);
        Result exists = byName(probe.compare()).get("leaky-exists");
        report(Map.of("leaky-exists", exists));

        assertThat(exists.changedPaths()).isEmpty();
        assertThat(exists.verdict()).isEqualTo(Verdict.SUSPECT);
        assertThat(exists.observerFindings()).extracting(Finding::table).contains("invoice");
    }

    @Test
    @DisplayName("9c. Filtresiz EXISTS, B'nin satırı varken: executor B'nin ilk satırında durur, iki katman da kör (CLEAN)")
    void unfilteredExistsStopsAtTheFirstRowAndBothLayersAreBlind() {
        DifferentialProbe probe = DifferentialProbe.recordBaseline(guard, TENANT_B, endpoints());
        seedTenantAsOwner(TENANT_A);
        Result exists = byName(probe.compare()).get("exists-any");
        report(Map.of("exists-any", exists));

        // B's rows come first in the heap, so A's rows are never read and nothing of A's is disclosed
        // for this data. With B empty the answer would flip false -> true and the diff would see it.
        assertThat(exists.verdict()).isEqualTo(Verdict.CLEAN);
    }

    private static Map<String, Result> byName(List<Result> results) {
        return results.stream().collect(Collectors.toMap(Result::endpoint, Function.identity()));
    }

    private static void report(Map<String, Result> results) {
        results.values().forEach(r -> System.out.printf("[scenario] %-17s %-7s changed=%s noise=%s observerTables=%s%n",
                r.endpoint(), r.verdict(), r.changedPaths(), r.noisePaths(),
                r.observerFindings().stream().map(Finding::table).distinct().toList()));
    }
}
