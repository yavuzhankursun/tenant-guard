package dev.tenantguard;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tenantguard.DifferentialProbe.Endpoint;
import dev.tenantguard.DifferentialProbe.Result;
import dev.tenantguard.DifferentialProbe.Verdict;
import dev.tenantguard.sample.InvoiceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Scenario 9: before/after diff. Starts with B's data only; A is seeded mid-test.
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
                new Endpoint("leaky-max", () -> jdbc.queryForObject("SELECT max(amount) FROM invoice", Long.class)));
    }

    @Test
    @DisplayName("9a. Önce/sonra farkı, A sıradan değerlerle: SUM ve liste kesin, JOIN susmuyor ama şüpheli, MAX kaçıyor")
    void beforeAfterDiffWithOrdinarySeed() {
        DifferentialProbe probe = DifferentialProbe.recordBaseline(guard, TENANT_B, endpoints());
        seedTenantAsOwner(TENANT_A, 1);
        Map<String, Result> results = byName(probe.compare());
        report(results);

        assertThat(results.get("leaky-sum-report").verdict()).isEqualTo(Verdict.LEAK);
        assertThat(results.get("leaky-sum-report").changedPaths()).containsExactly("$.total");
        assertThat(results.get("leaky-sum-report").noisePaths()).containsExactly("$.generatedAt");
        assertThat(results.get("leaky-list").verdict()).isEqualTo(Verdict.LEAK);
        assertThat(results.get("filtered-list").verdict()).isEqualTo(Verdict.CLEAN);
        // Diff is silent on the correctly filtered join, but the observer still flags it.
        assertThat(results.get("join-filtered").verdict()).isEqualTo(Verdict.SUSPECT);
        // A's amounts do not exceed B's, so MAX is unchanged: a real leak the diff cannot see.
        assertThat(results.get("leaky-max").verdict()).isEqualTo(Verdict.SUSPECT);
    }

    @Test
    @DisplayName("9b. Önce/sonra farkı, A uç değerlerle: MAX de kesin olur")
    void beforeAfterDiffWithExtremeSeed() {
        DifferentialProbe probe = DifferentialProbe.recordBaseline(guard, TENANT_B, endpoints());
        seedTenantAsOwner(TENANT_A, 1_000);
        Map<String, Result> results = byName(probe.compare());
        report(results);

        assertThat(results.get("leaky-max").verdict()).isEqualTo(Verdict.LEAK);
        assertThat(results.get("join-filtered").verdict()).isEqualTo(Verdict.SUSPECT);
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
