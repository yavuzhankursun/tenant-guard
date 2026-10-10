package dev.tenantguard;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Process-wide guard state. The actor is global rather than thread-local on purpose: the scanner
 * issues one request at a time, so the tenant context survives {@code @Async} and thread pools.
 */
public final class TenantGuard {

    /** Where in the JDBC call sequence a notice was collected; tells us whether the ORM got there first. */
    public enum HarvestPoint { EXECUTE, CLEAR_WARNINGS, CLOSE }

    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicReference<String> actor = new AtomicReference<>();
    private final List<Observation> observations = new CopyOnWriteArrayList<>();
    private final List<Finding> findings = new CopyOnWriteArrayList<>();
    private final Map<HarvestPoint, LongAdder> harvested = new ConcurrentHashMap<>();

    public void activate() {
        active.set(true);
    }

    public void deactivate() {
        active.set(false);
    }

    public boolean isActive() {
        return active.get();
    }

    public <T> T callAs(String tenant, Supplier<T> action) {
        String previous = actor.getAndSet(tenant);
        try {
            return action.get();
        } finally {
            actor.set(previous);
        }
    }

    public void runAs(String tenant, Runnable action) {
        callAs(tenant, () -> {
            action.run();
            return null;
        });
    }

    public List<Finding> findings() {
        return List.copyOf(findings);
    }

    public List<Finding> findings(Finding.Rule rule) {
        return findings.stream().filter(f -> f.rule() == rule).toList();
    }

    public List<Observation> observations() {
        return List.copyOf(observations);
    }

    public Map<HarvestPoint, Long> harvestedRows() {
        Map<HarvestPoint, Long> snapshot = new EnumMap<>(HarvestPoint.class);
        harvested.forEach((point, count) -> snapshot.put(point, count.sum()));
        return Map.copyOf(snapshot);
    }

    public void reset() {
        observations.clear();
        findings.clear();
        harvested.clear();
    }

    void record(String sql, List<RowRead> rows, HarvestPoint point) {
        if (rows.isEmpty()) {
            return;
        }
        harvested.computeIfAbsent(point, p -> new LongAdder()).add(rows.size());
        Observation observation = Observation.of(sql, actor.get(), rows);
        observations.add(observation);
        findings.addAll(Finding.evaluate(observation));
    }
}
