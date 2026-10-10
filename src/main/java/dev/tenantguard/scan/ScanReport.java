package dev.tenantguard.scan;

import dev.tenantguard.scan.ScanResult.Verdict;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable outcome of one scan; the assert methods are meant to be the last line of a test. */
public final class ScanReport {

    private static final Set<Verdict> LEAKS = EnumSet.of(Verdict.LEAK, Verdict.ERROR);
    private static final Set<Verdict> LEAKS_OR_SUSPECTS = EnumSet.of(Verdict.LEAK, Verdict.ERROR, Verdict.SUSPECT);

    private final String victim;
    private final String attacker;
    private final List<ScanResult> results;

    public ScanReport(String victim, String attacker, List<ScanResult> results) {
        this.victim = victim;
        this.attacker = attacker;
        this.results = List.copyOf(results);
    }

    public List<ScanResult> results() {
        return results;
    }

    public ScanResult result(String pathTemplate) {
        return results.stream()
                .filter(r -> r.pathTemplate().equals(pathTemplate))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Not scanned: " + pathTemplate));
    }

    public List<ScanResult> withVerdict(Verdict verdict) {
        return results.stream().filter(r -> r.verdict() == verdict).toList();
    }

    /** Fails on LEAK and on ERROR: an endpoint that crashed was not shown to be safe. */
    public void assertNoLeaks() {
        failOn(LEAKS);
    }

    /** Stricter: SUSPECT fails too (observer saw the victim's rows even though the response looked fine). */
    public void assertNoLeaksOrSuspects() {
        failOn(LEAKS_OR_SUSPECTS);
    }

    private void failOn(Set<Verdict> failing) {
        if (!results.isEmpty() && results.stream().allMatch(r -> r.verdict() == Verdict.SKIPPED)) {
            throw new AssertionError("No endpoint was verified: all %d SKIPPED%n%s".formatted(results.size(), table(results)));
        }
        List<ScanResult> failed = results.stream().filter(r -> failing.contains(r.verdict())).toList();
        if (failed.isEmpty()) {
            return;
        }
        String header = "Tenant isolation scan: %d of %d GET endpoint(s) failed for tenant %s against tenant %s's data (%s)"
                .formatted(failed.size(), results.size(), attacker, victim, counts(failed));
        String others = counts(results.stream().filter(r -> !failing.contains(r.verdict())).toList());
        throw new AssertionError(header + "\n" + table(failed)
                + (others.isEmpty() ? "" : "\nNot failing: " + others + " (full table: ScanReport.toString())"));
    }

    @Override
    public String toString() {
        return "Tenant isolation scan, tenant %s against tenant %s's data (%s)%n%s"
                .formatted(attacker, victim, counts(results), table(results));
    }

    private static String counts(List<ScanResult> rows) {
        Map<Verdict, Long> byVerdict = rows.stream()
                .collect(Collectors.groupingBy(ScanResult::verdict, () -> new EnumMap<>(Verdict.class), Collectors.counting()));
        return byVerdict.entrySet().stream()
                .map(e -> e.getValue() + " " + e.getKey())
                .collect(Collectors.joining(", "));
    }

    private static String table(List<ScanResult> rows) {
        int width = rows.stream().mapToInt(r -> r.pathTemplate().length()).max().orElse(0) + "GET ".length();
        String format = "  %-8s %-" + width + "s %-6s %s";
        String head = format.formatted("VERDICT", "ENDPOINT", "STATUS", "NOTE").stripTrailing();
        return rows.stream()
                .map(r -> format.formatted(r.verdict(), "GET " + r.pathTemplate(),
                        r.status() == 0 ? "-" : String.valueOf(r.status()), r.note()).stripTrailing())
                .collect(Collectors.joining("\n", head + "\n", ""));
    }
}
