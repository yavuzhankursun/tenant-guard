package dev.tenantguard;

import java.util.EnumMap;
import java.util.HashMap;
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
 *
 * <p>Trust: a notice counts only if it carries the nonce of an install this guard was told about
 * ({@link #trust}). The guard keeps the current nonce of each observed database, a handful at most:
 * several DataSources, routing targets, or schemas each have their own observer function, and a
 * re-install replaces only its own location's nonce, exactly as it replaces the function.
 */
public final class TenantGuard {

    /** Where in the JDBC call sequence a notice was collected; tells us whether the ORM got there first. */
    public enum HarvestPoint { EXECUTE, CLEAR_WARNINGS, CLOSE, FETCH }

    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicReference<String> actor = new AtomicReference<>();
    private final List<Observation> observations = new CopyOnWriteArrayList<>();
    private final List<Finding> findings = new CopyOnWriteArrayList<>();
    private final Map<HarvestPoint, LongAdder> harvested = new ConcurrentHashMap<>();
    private final Map<RowRead, LongAdder> reads = new ConcurrentHashMap<>();
    /** Location of an observer function to the nonce it raises; see {@link ObserverInstaller.Installation}. */
    private final Map<String, String> nonces = new ConcurrentHashMap<>();

    /**
     * Accepts the notices of {@code installation} from now on, and stops accepting the nonce its
     * location had before: the re-install replaced the function, so only a forger could still raise it.
     * Call it with what {@link ObserverInstaller#install} returned; the JUnit extension does.
     */
    public void trust(ObserverInstaller.Installation installation) {
        nonces.put(installation.location(), installation.nonce());
    }

    /** Whether notices carrying {@code nonce} are observations. A linear scan: a guard trusts a handful. */
    boolean trusts(String nonce) {
        return nonce != null && nonces.containsValue(nonce);
    }

    public void activate() {
        active.set(true);
    }

    public void deactivate() {
        active.set(false);
    }

    public boolean isActive() {
        return active.get();
    }

    /**
     * Runs {@code action} with {@code tenant} as the actor. Rows are matched against the tenant
     * column's {@code ::text} form exactly, with no normalization: {@code "2"} for an integer column,
     * the lower-case dashed form for a uuid ({@code String.valueOf} of the Java value gives both).
     * Normalizing numerics would merge distinct tenants of a text column ("02" and "2"); exact
     * comparison instead makes a mistyped actor fail loudly, reporting its own rows as foreign.
     */
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

    /**
     * How often each (table, tenant, kind) row was observed, reads and writes counted apart.
     * Observations collapse rows into sets, so only this count shows a harvest that lost or
     * double-counted notices.
     */
    public Map<RowRead, Long> rowReads() {
        Map<RowRead, Long> snapshot = new HashMap<>();
        reads.forEach((read, count) -> snapshot.put(read, count.sum()));
        return Map.copyOf(snapshot);
    }

    /** Forgets what was observed; the trusted nonces stay, they describe the database, not a test. */
    public void reset() {
        observations.clear();
        findings.clear();
        harvested.clear();
        reads.clear();
    }

    void record(String sql, List<RowRead> rows, HarvestPoint point) {
        if (rows.isEmpty()) {
            return;
        }
        harvested.computeIfAbsent(point, p -> new LongAdder()).add(rows.size());
        rows.forEach(row -> reads.computeIfAbsent(row, r -> new LongAdder()).increment());
        Observation observation = Observation.of(sql, actor.get(), rows);
        observations.add(observation);
        findings.addAll(Finding.evaluate(observation));
    }
}
