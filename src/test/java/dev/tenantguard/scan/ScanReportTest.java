package dev.tenantguard.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tenantguard.scan.ScanResult.Verdict;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pure unit tests: no database, no Spring. */
class ScanReportTest {

    private static ScanResult result(String template, int status, Verdict verdict, String note) {
        return new ScanResult(Endpoint.of(template, "Test#handler"), template, status, verdict, Set.of(), List.of(), note);
    }

    @Test
    @DisplayName("CLEAN, SUSPECT ve SKIPPED assertNoLeaks'i geçer; SUSPECT sıkı kontrolde düşer")
    void suspectsOnlyFailTheStrictAssertion() {
        ScanReport report = new ScanReport("A", "B", List.of(
                result("/a", 200, Verdict.CLEAN, ""),
                result("/b", 404, Verdict.SUSPECT, "observer: invoice rows of tenant A read by select 1"),
                result("/c/{id}", 0, Verdict.SKIPPED, "no table c")));

        assertThatCode(report::assertNoLeaks).doesNotThrowAnyException();
        assertThatThrownBy(report::assertNoLeaksOrSuspects)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("SUSPECT")
                .hasMessageContaining("GET /b")
                .hasMessageNotContaining("GET /a ");
    }

    @Test
    @DisplayName("ERROR asla temiz sayılmaz: assertNoLeaks düşer")
    void errorsAreNeverClean() {
        ScanReport report = new ScanReport("A", "B", List.of(result("/boom", 500, Verdict.ERROR, "HTTP 500")));

        assertThatThrownBy(report::assertNoLeaks)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ERROR")
                .hasMessageContaining("GET /boom");
    }

    @Test
    @DisplayName("Rapor değiştirilemez ve tüm sonuçları tablo olarak basar")
    void reportIsImmutableAndRendersEveryRow() {
        List<ScanResult> source = new ArrayList<>(List.of(result("/a", 200, Verdict.CLEAN, "")));
        ScanReport report = new ScanReport("A", "B", source);
        source.clear();

        assertThat(report.results()).hasSize(1);
        assertThatThrownBy(() -> report.results().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(report.toString()).contains("CLEAN", "GET /a", "200");
        assertThatThrownBy(() -> report.result("/missing")).isInstanceOf(IllegalArgumentException.class);
    }
}
