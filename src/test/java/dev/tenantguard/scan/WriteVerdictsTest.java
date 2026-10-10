package dev.tenantguard.scan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tenantguard.Finding;
import dev.tenantguard.scan.HttpCaller.Response;
import dev.tenantguard.scan.ScanResult.Verdict;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMethod;

/** Write verdicts: the victim's data decides, the status only explains. */
class WriteVerdictsTest {

    private static final Endpoint PUT = Endpoint.of(RequestMethod.PUT, "/x/{id}", "X#put", null);
    private static final String PATH = "/x/3";
    private static final List<Finding> READ_OF_A = List.of(
            new Finding(Finding.Rule.ACTOR_MISMATCH, "update x set n=? where id=?", "x", Set.of("A")));

    private static ScanResult classify(int status, List<String> changes, List<Finding> findings) {
        return WriteVerdicts.classify(PUT, PATH, new Response(status, null, null), changes, findings, "A", "B", null);
    }

    @Test
    @DisplayName("Kurbanın verisi değiştiyse status ne olursa olsun LEAK: 2xx, 4xx, hatta 500")
    void victimDataChangeIsLeakWhateverTheStatus() {
        for (int status : new int[] {204, 404, 500}) {
            ScanResult result = classify(status, List.of("x: 1 row updated"), List.of());
            assertThat(result.verdict()).as("status " + status).isEqualTo(Verdict.LEAK);
            assertThat(result.note()).as("status " + status).contains("tenant A's data changed: x: 1 row updated");
        }
        assertThat(classify(204, List.of("x: 1 row updated"), READ_OF_A).note()).contains("observer: x rows of tenant A");
    }

    @Test
    @DisplayName("Değişiklik yok: 2xx/404 CLEAN, okuma görüldüyse SUSPECT, 5xx ERROR, 401 SKIPPED")
    void noChangeClassifiesByStatusAndObserver() {
        assertThat(classify(204, List.of(), List.of()).verdict()).isEqualTo(Verdict.CLEAN);
        assertThat(classify(404, List.of(), List.of()).verdict()).isEqualTo(Verdict.CLEAN);
        assertThat(classify(404, List.of(), List.of()).note()).isEqualTo("404, tenant A's data unchanged");
        assertThat(classify(403, List.of(), READ_OF_A).verdict()).isEqualTo(Verdict.SUSPECT);
        assertThat(classify(503, List.of(), List.of()).verdict()).isEqualTo(Verdict.ERROR);
        ScanResult crashed = WriteVerdicts.classify(PUT, PATH, new Response(500, null, "IllegalStateException: boom"),
                List.of(), List.of(), "A", "B", null);
        assertThat(crashed.verdict()).isEqualTo(Verdict.ERROR);
        assertThat(crashed.note()).contains("boom");
        assertThat(classify(401, List.of(), List.of()).verdict()).isEqualTo(Verdict.SKIPPED);
    }

    @Test
    @DisplayName("400/415/422: gövde reddedildi, veriye ulaşılmadı -> SKIPPED (CLEAN sessiz bir geçiş olurdu)")
    void rejectedBodyIsNotVerified() {
        for (int status : new int[] {400, 415, 422}) {
            ScanResult result = classify(status, List.of(), List.of());
            assertThat(result.verdict()).as("status " + status).isEqualTo(Verdict.SKIPPED);
            assertThat(result.note()).contains("rejected", ".body(\"PUT /x/{id}\"");
        }
    }

    @Test
    @DisplayName("Spoof probe'unda 400 doğru bir red: CLEAN; not spoof edilen alanı söyler")
    void spoofRefusalIsClean() {
        ScanResult refused = WriteVerdicts.classify(PUT, PATH, new Response(400, null, null), List.of(), List.of(),
                "A", "B", "tenantId=A");

        assertThat(refused.verdict()).isEqualTo(Verdict.CLEAN);
        assertThat(refused.note()).startsWith("spoofed tenantId=A: 400");
    }

    @Test
    @DisplayName("İki probe birleşir: kötü olan kazanır (LEAK > ERROR > SUSPECT > SKIPPED > CLEAN)")
    void combineKeepsTheWorse() {
        ScanResult clean = classify(201, List.of(), List.of());
        ScanResult leak = WriteVerdicts.classify(PUT, PATH, new Response(201, null, null),
                List.of("x: 1 row created with tenant A"), List.of(), "A", "B", "tenantId=A");

        assertThat(WriteVerdicts.combine(clean, leak)).isSameAs(leak);
        assertThat(WriteVerdicts.combine(leak, clean)).isSameAs(leak);
        assertThat(WriteVerdicts.combine(clean, classify(201, List.of(), List.of())).note())
                .isEqualTo("201, tenant A's data unchanged; 201, tenant A's data unchanged");
        assertThat(WriteVerdicts.combine(classify(422, List.of(), List.of()), clean).verdict()).isEqualTo(Verdict.SKIPPED);
    }
}
