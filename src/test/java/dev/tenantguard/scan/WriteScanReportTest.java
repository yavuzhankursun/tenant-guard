package dev.tenantguard.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tenantguard.scan.ScanResult.Verdict;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMethod;

/** Write rows share the GET table: the method is part of the endpoint column and of the lookup key. */
class WriteScanReportTest {

    private static ScanResult result(RequestMethod method, String template, Verdict verdict, String note) {
        return new ScanResult(Endpoint.of(method, template, "T#h", null), template, 204, verdict, Set.of(), List.of(), note);
    }

    private final ScanReport report = new ScanReport("A", "B", List.of(
            result(RequestMethod.GET, "/w/invoices/{id}", Verdict.CLEAN, ""),
            result(RequestMethod.PUT, "/w/invoices/{id}", Verdict.LEAK, "tenant A's data changed: invoice: 1 row updated"),
            result(RequestMethod.DELETE, "/w/invoices/{id}", Verdict.CLEAN, ""),
            result(RequestMethod.PUT, "/w/my/invoices/{id}", Verdict.CLEAN, ""),
            result(RequestMethod.DELETE, "/w/my/invoices/{id}", Verdict.CLEAN, "")));

    @Test
    @DisplayName("result(\"PUT /x\") method'la eşler; yalnız template GET'i tercih eder, GET yoksa ve birden çoksa belirsiz")
    void looksUpByMethodAndTemplate() {
        assertThat(report.result("PUT /w/invoices/{id}").verdict()).isEqualTo(Verdict.LEAK);
        assertThat(report.result("/w/invoices/{id}").endpoint().method()).isEqualTo(RequestMethod.GET);
        assertThatThrownBy(() -> report.result("/w/my/invoices/{id}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PUT /w/my/invoices/{id}")
                .hasMessageContaining("DELETE /w/my/invoices/{id}");
        assertThatThrownBy(() -> report.result("PATCH /w/invoices/{id}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Tablo ve assertNoLeaks method'u basar; başlık yazma satırı varken 'GET' demez")
    void tableShowsTheMethod() {
        assertThat(report.toString()).contains("PUT /w/invoices/{id}", "DELETE /w/my/invoices/{id}", "GET /w/invoices/{id}");
        assertThatThrownBy(report::assertNoLeaks)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("1 of 5 endpoint(s) failed")
                .hasMessageContaining("LEAK     PUT /w/invoices/{id}");
    }
}
