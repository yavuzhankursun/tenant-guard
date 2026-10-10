package dev.tenantguard.scan;

import dev.tenantguard.DifferentialProbe;
import dev.tenantguard.Finding;
import dev.tenantguard.scan.HttpCaller.Response;
import dev.tenantguard.scan.ScanResult.Verdict;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Turns raw call outcomes into verdicts and one-line notes a reviewer can act on. */
final class Verdicts {

    private static final int MAX_LISTED = 3;
    private static final int MAX_SQL = 90;
    private static final int UNAUTHORIZED = 401;

    private record Source(String table, String sql) {
    }

    private Verdicts() {
    }

    static ScanResult skipped(Endpoint endpoint, String reason) {
        return new ScanResult(endpoint, null, 0, Verdict.SKIPPED, Set.of(), List.of(), reason);
    }

    static ScanResult excluded(Endpoint endpoint, String rule) {
        return new ScanResult(endpoint, null, 0, Verdict.EXCLUDED, Set.of(), List.of(), "excluded by rule " + rule);
    }

    /** Diff-scored endpoint: {@code baseline} are the calls before the victim existed, {@code current} the one after. */
    static ScanResult listing(Endpoint endpoint, List<Response> baseline, Response current,
                              DifferentialProbe.Result diff, String victim, String attacker) {
        List<Finding> foreign = actorMismatches(diff.observerFindings());
        Optional<Response> failed = Stream.concat(baseline.stream(), Stream.of(current)).filter(Response::isError).findFirst();
        if (failed.isPresent()) {
            return result(endpoint, null, failed.get().status(), Verdict.ERROR, Set.of(), foreign, failed.get().describeError());
        }
        if (current.status() == UNAUTHORIZED) {
            return unauthenticated(endpoint, null, foreign, attacker);
        }
        int before = baseline.getLast().status();
        Set<String> changed = new TreeSet<>(diff.changedPaths());
        if (!changed.isEmpty() || before != current.status()) {
            String note = join(before != current.status() ? "status %d -> %d".formatted(before, current.status()) : "",
                    changed.isEmpty() ? "" : "response changed after tenant %s's data was added: %s"
                            .formatted(victim, abbreviate(changed)),
                    observerNote(foreign));
            return result(endpoint, null, current.status(), Verdict.LEAK, changed, foreign, note);
        }
        if (!foreign.isEmpty()) {
            return result(endpoint, null, current.status(), Verdict.SUSPECT, changed, foreign,
                    "response unchanged, but " + observerNote(foreign));
        }
        return result(endpoint, null, current.status(), Verdict.CLEAN, changed, foreign, "");
    }

    /** Id-scored endpoint: the attacker asked for a resource the victim owns. */
    static ScanResult byId(Endpoint endpoint, String path, Response response, List<Finding> findings,
                           String victim, String attacker) {
        List<Finding> foreign = actorMismatches(findings);
        int status = response.status();
        if (response.isError()) {
            return result(endpoint, path, status, Verdict.ERROR, Set.of(), foreign, response.describeError());
        }
        if (status == UNAUTHORIZED) {
            return unauthenticated(endpoint, path, foreign, attacker);
        }
        if (status >= 200 && status < 300 && EmptyBody.isEmpty(response.body())) {
            return emptySuccess(endpoint, path, response, foreign, victim);
        }
        if (status >= 200 && status < 300) {
            return result(endpoint, path, status, Verdict.LEAK, Set.of(), foreign,
                    join("%d for tenant %s's %s".formatted(status, victim, path), observerNote(foreign)));
        }
        if (!foreign.isEmpty()) {
            return result(endpoint, path, status, Verdict.SUSPECT, Set.of(), foreign,
                    "%d for tenant %s's %s, but %s".formatted(status, victim, path, observerNote(foreign)));
        }
        return result(endpoint, path, status, Verdict.CLEAN, Set.of(), foreign, "");
    }

    /**
     * 2xx without data for the victim's id is what a correctly filtered query looks like, so it is no
     * LEAK by itself; only the observer can still tell that the victim's rows were read on the way.
     */
    private static ScanResult emptySuccess(Endpoint endpoint, String path, Response response, List<Finding> foreign,
                                           String victim) {
        String note = "%d with empty body %s for tenant %s's %s"
                .formatted(response.status(), EmptyBody.render(response.body()), victim, path);
        return foreign.isEmpty()
                ? result(endpoint, path, response.status(), Verdict.CLEAN, Set.of(), foreign, note)
                : result(endpoint, path, response.status(), Verdict.SUSPECT, Set.of(), foreign,
                        note + ", but " + observerNote(foreign));
    }

    static List<Finding> actorMismatches(List<Finding> findings) {
        return findings.stream().filter(f -> f.rule() == Finding.Rule.ACTOR_MISMATCH).toList();
    }

    /** "observer: invoice rows of tenant A read by select ...", one clause per distinct table and statement. */
    static String observerNote(List<Finding> findings) {
        if (findings.isEmpty()) {
            return "";
        }
        Map<Source, Set<String>> tenantsBySource = new LinkedHashMap<>();
        findings.forEach(f -> tenantsBySource.computeIfAbsent(new Source(f.table(), f.sql()), k -> new TreeSet<>())
                .addAll(f.tenants()));
        List<String> clauses = tenantsBySource.entrySet().stream()
                .map(e -> "%s rows of tenant %s read by %s".formatted(e.getKey().table(),
                        String.join(",", e.getValue()), abbreviateSql(e.getKey().sql())))
                .toList();
        String listed = String.join("; ", clauses.subList(0, Math.min(2, clauses.size())));
        return "observer: " + listed + (clauses.size() > 2 ? " (+%d more)".formatted(clauses.size() - 2) : "");
    }

    /** A wrong TenantAuthenticator turns every call into 401; calling that CLEAN would be a silent pass. */
    private static ScanResult unauthenticated(Endpoint endpoint, String path, List<Finding> foreign, String attacker) {
        return result(endpoint, path, UNAUTHORIZED, Verdict.SKIPPED, Set.of(), foreign,
                "tenant %s itself got 401: check the TenantAuthenticator; not verified".formatted(attacker));
    }

    private static ScanResult result(Endpoint endpoint, String path, int status, Verdict verdict,
                                     Set<String> changed, List<Finding> findings, String note) {
        return new ScanResult(endpoint, path, status, verdict, changed, findings, note);
    }

    private static String abbreviate(Set<String> paths) {
        String listed = paths.stream().limit(MAX_LISTED).collect(Collectors.joining(", "));
        return paths.size() > MAX_LISTED ? listed + " (+%d more)".formatted(paths.size() - MAX_LISTED) : listed;
    }

    private static String abbreviateSql(String sql) {
        String flat = sql == null ? "<unknown statement>" : sql.replaceAll("\\s+", " ").strip();
        return flat.length() <= MAX_SQL ? flat : flat.substring(0, MAX_SQL) + "...";
    }

    private static String join(String... parts) {
        return Stream.of(parts).filter(p -> !p.isBlank()).collect(Collectors.joining("; "));
    }
}
