package dev.tenantguard.scan;

import dev.tenantguard.Finding;
import dev.tenantguard.scan.HttpCaller.Response;
import dev.tenantguard.scan.ScanResult.Verdict;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Verdicts for a write called as the attacker on the victim's ids. The victim's data decides: a
 * changed, deleted or newly created row of the victim is a LEAK whatever the status said (an
 * endpoint can write and then fail, or answer 404 after an unfiltered update). Without a change,
 * the status and the observer explain why: refused or no-op is CLEAN, a read of the victim's rows
 * on the way is SUSPECT, a crash is ERROR.
 */
final class WriteVerdicts {

    private static final int UNAUTHORIZED = 401;
    /**
     * The handler rejected the request itself (body, content type, validation) before touching any
     * data. For the plain probe that means "not verified", not "safe": a made-up body that fails
     * validation would otherwise turn every write endpoint CLEAN.
     */
    private static final Set<Integer> REJECTED = Set.of(400, 415, 422);

    /**
     * ACTOR_MISMATCH, plus WRITE_MISMATCH where the core has it (the attacker wrote a row of another
     * tenant). Matched by name so this compiles against cores with and without that rule.
     */
    private static final Set<String> FOREIGN_RULES = Set.of("ACTOR_MISMATCH", "WRITE_MISMATCH");

    /** Lower is worse; combine keeps the worse of two probes. */
    private static final List<Verdict> SEVERITY =
            List.of(Verdict.LEAK, Verdict.ERROR, Verdict.SUSPECT, Verdict.SKIPPED, Verdict.CLEAN, Verdict.EXCLUDED);

    private WriteVerdicts() {
    }

    /**
     * @param changes {@link RowSnapshot#victimChanges} of this call
     * @param spoofed {@code tenantId=A} for the spoof probe, null for the plain one
     */
    static ScanResult classify(Endpoint endpoint, String path, Response response, List<String> changes,
                               List<Finding> findings, String victim, String attacker, String spoofed) {
        List<Finding> foreign = foreign(findings);
        String prefix = spoofed == null ? "" : "spoofed " + spoofed + ": ";
        int status = response.status();
        String observer = Verdicts.observerNote(foreign);
        if (!changes.isEmpty()) {
            String note = join("tenant %s's data changed: %s".formatted(victim, String.join(", ", changes)), observer);
            return result(endpoint, path, status, Verdict.LEAK, foreign, prefix + note);
        }
        if (response.isError()) {
            return result(endpoint, path, status, Verdict.ERROR, foreign, prefix + response.describeError());
        }
        if (status == UNAUTHORIZED) {
            return result(endpoint, path, status, Verdict.SKIPPED, foreign, prefix
                    + "tenant %s itself got 401: check the TenantAuthenticator; not verified".formatted(attacker));
        }
        String unchanged = "%d, tenant %s's data unchanged".formatted(status, victim);
        if (!foreign.isEmpty()) {
            return result(endpoint, path, status, Verdict.SUSPECT, foreign, prefix + unchanged + ", but " + observer);
        }
        if (spoofed == null && REJECTED.contains(status)) {
            return result(endpoint, path, status, Verdict.SKIPPED, foreign, ("%d: request rejected before it reached "
                    + "any data; not verified; supply .body(\"%s\", tenant -> json)").formatted(status, endpoint.label()));
        }
        return result(endpoint, path, status, Verdict.CLEAN, foreign, prefix + unchanged);
    }

    /** The worse of the plain and the spoof probe; two equally ranked results keep both notes. */
    static ScanResult combine(ScanResult plain, ScanResult spoof) {
        int plainRank = SEVERITY.indexOf(plain.verdict());
        int spoofRank = SEVERITY.indexOf(spoof.verdict());
        if (plainRank != spoofRank) {
            return plainRank < spoofRank ? plain : spoof;
        }
        return new ScanResult(plain.endpoint(), plain.resolvedPath(), plain.status(), plain.verdict(), Set.of(),
                Stream.concat(plain.observerFindings().stream(), spoof.observerFindings().stream()).toList(),
                join(plain.note(), spoof.note()));
    }

    static List<Finding> foreign(List<Finding> findings) {
        return findings.stream().filter(f -> FOREIGN_RULES.contains(f.rule().name())).toList();
    }

    private static ScanResult result(Endpoint endpoint, String path, int status, Verdict verdict,
                                     List<Finding> findings, String note) {
        return new ScanResult(endpoint, path, status, verdict, Set.of(), findings, note);
    }

    private static String join(String... parts) {
        return Stream.of(parts).filter(p -> !p.isBlank()).collect(Collectors.joining("; "));
    }
}
