package dev.tenantguard;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Before/after check: another tenant's data must not change the actor's responses. Record the
 * actor's responses with only its own data, seed the other tenant, call again and diff. Fields that
 * already differ between two baseline calls (timestamps, request ids) are masked as noise.
 * Observer findings collected during the second call are attached as diagnosis.
 */
public final class DifferentialProbe {

    public record Endpoint(String name, Supplier<Object> call) {
    }

    /** LEAK: response changed. SUSPECT: response unchanged but the observer saw foreign rows. */
    public enum Verdict { LEAK, SUSPECT, CLEAN }

    public record Result(String endpoint, Verdict verdict, Set<String> changedPaths, Set<String> noisePaths,
                         List<Finding> observerFindings) {
    }

    private record Baseline(Map<String, String> snapshot, Set<String> noise) {
    }

    private final TenantGuard guard;
    private final String actor;
    private final List<Endpoint> endpoints;
    private final Map<String, Baseline> baselines;

    private DifferentialProbe(TenantGuard guard, String actor, List<Endpoint> endpoints,
                              Map<String, Baseline> baselines) {
        this.guard = guard;
        this.actor = actor;
        this.endpoints = endpoints;
        this.baselines = baselines;
    }

    /** Call while only the actor's data exists. Each endpoint is called twice to learn its noise. */
    public static DifferentialProbe recordBaseline(TenantGuard guard, String actor, List<Endpoint> endpoints) {
        Map<String, Baseline> baselines = new LinkedHashMap<>();
        for (Endpoint endpoint : endpoints) {
            Map<String, String> first = snapshot(guard, actor, endpoint);
            Map<String, String> second = snapshot(guard, actor, endpoint);
            baselines.put(endpoint.name(), new Baseline(first, diff(first, second)));
        }
        return new DifferentialProbe(guard, actor, List.copyOf(endpoints), Map.copyOf(baselines));
    }

    /** Call after the other tenant's data has been added. */
    public List<Result> compare() {
        return endpoints.stream().map(this::compare).toList();
    }

    private Result compare(Endpoint endpoint) {
        Baseline baseline = baselines.get(endpoint.name());
        int findingsBefore = guard.findings().size();
        Map<String, String> current = snapshot(guard, actor, endpoint);
        List<Finding> all = guard.findings();
        List<Finding> observed = List.copyOf(all.subList(findingsBefore, all.size()));

        Set<String> changed = new TreeSet<>(diff(baseline.snapshot(), current));
        changed.removeAll(baseline.noise());
        Verdict verdict = !changed.isEmpty() ? Verdict.LEAK : observed.isEmpty() ? Verdict.CLEAN : Verdict.SUSPECT;
        return new Result(endpoint.name(), verdict, Set.copyOf(changed), Set.copyOf(baseline.noise()), observed);
    }

    private static Map<String, String> snapshot(TenantGuard guard, String actor, Endpoint endpoint) {
        Map<String, String> flat = new TreeMap<>();
        flatten("$", guard.callAs(actor, endpoint.call()), flat);
        return Map.copyOf(flat);
    }

    private static void flatten(String path, Object value, Map<String, String> out) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, child) -> flatten(path + "." + key, child, out));
        } else if (value instanceof Collection<?> items) {
            out.put(path + ".size", String.valueOf(items.size()));
            int index = 0;
            for (Object item : items) {
                flatten(path + "[" + index++ + "]", item, out);
            }
        } else {
            out.put(path, String.valueOf(value));
        }
    }

    private static Set<String> diff(Map<String, String> left, Map<String, String> right) {
        Set<String> paths = new HashSet<>(left.keySet());
        paths.addAll(right.keySet());
        paths.removeIf(path -> String.valueOf(left.get(path)).equals(String.valueOf(right.get(path)))
                && left.containsKey(path) == right.containsKey(path));
        return Set.copyOf(paths);
    }
}
