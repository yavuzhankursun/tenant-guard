package dev.tenantguard.scan;

import dev.tenantguard.Finding;
import dev.tenantguard.TenantGuard;
import dev.tenantguard.TenantSeeder;
import dev.tenantguard.scan.EndpointScanner.Target;
import dev.tenantguard.scan.HttpCaller.Response;
import dev.tenantguard.scan.RequestBodies.Body;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * The write phase: every POST, PUT, PATCH and DELETE is called once as the attacker with the
 * victim's ids, between two snapshots of all tenant tables, and judged by what happened to the
 * victim's rows ({@link WriteVerdicts}).
 *
 * <p>Isolation: before a call, both tenants' rows are deleted and re-seeded as the owner whenever
 * the previous call changed anything, so no write sees another write's effect. A call that changed
 * nothing (the common 404) leaves the seed reusable, which keeps the cost at roughly one re-seed
 * per write that actually wrote. Re-seeding with the user's own seeder was chosen over rolling back
 * a transaction because MockMvc requests run the application's own transactions on pooled
 * connections: there is no single transaction to roll back without changing the app. DELETEs run
 * last regardless of list order, so a failed reset can never hide earlier endpoints.
 *
 * <p>One instance per scan: {@link #current} is the run's knowledge of the database.
 */
final class WriteScan {

    /** What {@code includeWrites} and its companions configured. {@code reset} null means delete by tenant. */
    record Settings(DataSource dataSource, String tenantColumn, RequestBodies bodies, TenantFields tenantFields,
                    Runnable reset) {
    }

    @FunctionalInterface
    private interface Sql<T> {
        T run(Connection owner) throws SQLException;
    }

    private record Fresh(Target target, RowSnapshot before) {
    }

    private final TenantGuard guard;
    private final TenantSeeder seeder;
    private final HttpCaller http;
    private final Function<Endpoint, Target> targets;
    private final Settings settings;
    private final String victim;
    private final String attacker;
    private TenantTables tables;
    /** The tables' state as of the last snapshot; null when unknown or dirty, i.e. a reset is due. */
    private RowSnapshot current;

    WriteScan(TenantGuard guard, TenantSeeder seeder, HttpCaller http, Function<Endpoint, Target> targets,
              Settings settings, String victim, String attacker) {
        this.guard = guard;
        this.seeder = seeder;
        this.http = http;
        this.targets = targets;
        this.settings = settings;
        this.victim = victim;
        this.attacker = attacker;
    }

    /** Per-endpoint bodies by label ({@code PUT /x/{id}}) in front of a fallback strategy. */
    static RequestBodies withOverrides(Map<String, Function<String, String>> overrides, RequestBodies fallback) {
        Map<String, Function<String, String>> copy = Map.copyOf(overrides);
        return (endpoint, tenant) -> {
            Function<String, String> override = copy.get(endpoint.label());
            if (override == null) {
                return fallback.body(endpoint, tenant);
            }
            String json = override.apply(tenant);
            return json == null ? Body.unavailable("body for %s returned null".formatted(endpoint.label())) : Body.of(json);
        };
    }

    /** Leaves both tenants seeded, as the read phase does. */
    Map<Endpoint, ScanResult> scan(List<Endpoint> writes) {
        tables = sql(owner -> TenantTables.discover(owner, settings.tenantColumn()), "discovering tenant tables");
        Map<Endpoint, ScanResult> results = new LinkedHashMap<>();
        writes.stream().sorted(Comparator.comparing(Endpoint::method)).forEach(e -> results.put(e, scanOne(e)));
        if (current == null) {
            EndpointScanner.asOwner(guard, this::reseed);
        }
        return results;
    }

    private ScanResult scanOne(Endpoint endpoint) {
        Body body = body(endpoint);
        if (!body.isAvailable()) {
            return Verdicts.skipped(endpoint, body.reason());
        }
        String plainJson = body.generated() ? settings.tenantFields().strip(body.json()) : body.json();
        ScanResult plain = probe(endpoint, plainJson, null);
        if (endpoint.method() != RequestMethod.POST || plain.status() == 0) {
            return plain;
        }
        return settings.tenantFields().spoof(body.json(), victim)
                .map(spoof -> WriteVerdicts.combine(plain, probe(endpoint, spoof.json(), spoof.describe())))
                .orElse(plain);
    }

    /** A broken user body function must not abort the scan; it turns into a SKIPPED row with the reason. */
    private Body body(Endpoint endpoint) {
        try {
            Body body = settings.bodies().body(endpoint, attacker);
            return body != null ? body : Body.unavailable("body source returned null for " + endpoint.label());
        } catch (RuntimeException e) {
            return Body.unavailable("body for %s failed: %s: %s"
                    .formatted(endpoint.label(), e.getClass().getSimpleName(), e.getMessage()));
        }
    }

    private ScanResult probe(Endpoint endpoint, String json, String spoofed) {
        Fresh fresh = EndpointScanner.asOwner(guard, () -> fresh(endpoint));
        String path = fresh.target().path();
        if (path == null) {
            return Verdicts.skipped(endpoint, fresh.target().skipReason());
        }
        int before = guard.findings().size();
        Response response = guard.callAs(attacker, () -> http.send(endpoint.method(), path, json, attacker));
        List<Finding> all = guard.findings();
        List<Finding> during = List.copyOf(all.subList(Math.min(before, all.size()), all.size()));
        RowSnapshot after = EndpointScanner.asOwner(guard, this::snapshot);
        current = after.equals(fresh.before()) ? fresh.before() : null;
        return WriteVerdicts.classify(endpoint, path, response, fresh.before().victimChanges(after, victim), during,
                victim, attacker, spoofed);
    }

    /** Runs as the owner: re-seeds if the last call wrote, then resolves the victim's ids on that seed. */
    private Fresh fresh(Endpoint endpoint) {
        if (current == null) {
            reseed();
        }
        return new Fresh(targets.apply(endpoint), current);
    }

    private Void reseed() {
        if (settings.reset() != null) {
            settings.reset().run();
        } else {
            sql(owner -> {
                tables.deleteTenants(owner, List.of(victim, attacker));
                return null;
            }, "deleting tenant %s's and %s's rows (pass resetWith(...) if other tables reference them)"
                    .formatted(victim, attacker));
        }
        seeder.seed(attacker);
        seeder.seed(victim);
        current = snapshot();
        return null;
    }

    private RowSnapshot snapshot() {
        return sql(tables::snapshot, "snapshotting tenant tables " + tables.labels());
    }

    private <T> T sql(Sql<T> action, String what) {
        try (Connection owner = settings.dataSource().getConnection()) {
            return action.run(owner);
        } catch (SQLException e) {
            throw new IllegalStateException("Write scan failed %s: %s".formatted(what, e.getMessage()), e);
        }
    }
}
