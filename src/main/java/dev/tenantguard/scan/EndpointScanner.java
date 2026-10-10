package dev.tenantguard.scan;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tenantguard.DifferentialProbe;
import dev.tenantguard.Finding;
import dev.tenantguard.TenantGuard;
import dev.tenantguard.TenantSeeder;
import dev.tenantguard.scan.HttpCaller.Response;
import dev.tenantguard.scan.ResourceIds.Resolution;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import java.util.stream.Collectors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.util.UriUtils;

/**
 * Calls every GET endpoint as the attacker (B) and checks whether the victim's (A) data shows up.
 * Phases: seed B as owner; baseline the endpoints without path variables; seed A as owner and resolve
 * A's ids; call everything again (A's ids substituted) and classify. The guard is inactive only while
 * seeding and resolving ids, so owner writes are never observed. Calls are strictly sequential:
 * the guard's actor is process-wide. Expects an armed guard (observer installed, self-check passed).
 * Endpoints matching an exclusion rule are never called, not even for the baseline, and are reported
 * as EXCLUDED with the rule. Write endpoints (POST, PUT, PATCH, DELETE) run after all reads, only
 * with {@link Builder#includeWrites}, and are judged by the victim's data ({@link WriteScan}).
 */
public final class EndpointScanner {

    /** The victim's path for an endpoint, or why there is none. */
    record Target(String path, String skipReason) {
    }

    /** {@code rule} is what the report prints: the Ant pattern or the user's description. */
    private record Exclusion(String rule, Predicate<Endpoint> matches) {
    }

    private static final AntPathMatcher TEMPLATES = new AntPathMatcher();
    private static final Pattern WRITE_LABEL = Pattern.compile("(POST|PUT|PATCH|DELETE) /\\S*");

    private final TenantGuard guard;
    private final TenantSeeder seeder;
    private final ResourceIds resourceIds;
    private final HttpCaller http;
    private final List<Endpoint> endpoints;
    private final List<Exclusion> exclusions;
    private final String victim;
    private final String attacker;
    /** Null when writes are off. */
    private final WriteScan.Settings writes;

    private EndpointScanner(Builder builder) {
        this.guard = builder.guard;
        this.seeder = builder.seeder;
        this.resourceIds = builder.resourceIds;
        this.http = new HttpCaller(builder.mockMvc, builder.authenticator, builder.objectMapper);
        this.endpoints = builder.endpoints;
        this.exclusions = List.copyOf(builder.exclusions);
        this.victim = builder.victim;
        this.attacker = builder.attacker;
        this.writes = builder.writeSettings();
    }

    public static Builder builder(MockMvc mockMvc, TenantGuard guard) {
        return new Builder(mockMvc, guard);
    }

    /** Leaves the guard in the state it found it; the tables keep both tenants' data afterwards. */
    public ScanReport scan() {
        boolean wasActive = guard.isActive();
        try {
            Map<Endpoint, String> excluded = excludedRules();
            Map<Endpoint, ScanResult> results = new HashMap<>(scanReads(
                    endpoints.stream().filter(e -> !e.isWrite() && !excluded.containsKey(e)).toList()));
            List<Endpoint> scannedWrites = endpoints.stream().filter(e -> e.isWrite() && !excluded.containsKey(e)).toList();
            if (!scannedWrites.isEmpty()) {
                results.putAll(new WriteScan(guard, seeder, http, this::target, writes, victim, attacker).scan(scannedWrites));
            }
            excluded.forEach((endpoint, rule) -> results.put(endpoint, Verdicts.excluded(endpoint, rule)));
            return new ScanReport(victim, attacker, endpoints.stream().map(results::get).toList());
        } finally {
            if (wasActive) {
                guard.activate();
            } else {
                guard.deactivate();
            }
        }
    }

    /** The GET phases; templates are unique among reads. */
    private Map<Endpoint, ScanResult> scanReads(List<Endpoint> scanned) {
        asOwner(() -> {
            seeder.seed(attacker);
            return null;
        });
        List<Endpoint> listings = scanned.stream().filter(e -> !e.hasPathVariables()).toList();
        Map<String, List<Response>> responses = new HashMap<>();
        DifferentialProbe probe = DifferentialProbe.recordBaseline(guard, attacker, probeEndpoints(listings, responses));
        Map<String, Target> targets = asOwner(() -> {
            seeder.seed(victim);
            return resolveTargets(scanned);
        });
        Map<String, DifferentialProbe.Result> diffs = probe.compare().stream()
                .collect(Collectors.toMap(DifferentialProbe.Result::endpoint, Function.identity()));
        Map<Endpoint, ScanResult> results = new HashMap<>();
        scanned.forEach(e -> results.put(e, e.hasPathVariables() ? scanById(e, targets.get(e.pathTemplate()))
                : scanListing(e, responses.get(e.pathTemplate()), diffs.get(e.pathTemplate()))));
        return results;
    }

    /** Seeding and id lookups write/read as the owner: an observed seeder would drown the real findings. */
    private <T> T asOwner(Supplier<T> action) {
        return asOwner(guard, action);
    }

    static <T> T asOwner(TenantGuard guard, Supplier<T> action) {
        guard.deactivate();
        try {
            return action.get();
        } finally {
            guard.activate();
        }
    }

    private List<DifferentialProbe.Endpoint> probeEndpoints(List<Endpoint> listings, Map<String, List<Response>> log) {
        return listings.stream().map(endpoint -> new DifferentialProbe.Endpoint(endpoint.pathTemplate(), () -> {
            Response response = http.get(endpoint.pathTemplate(), attacker);
            log.computeIfAbsent(endpoint.pathTemplate(), k -> new ArrayList<>()).add(response);
            return response.failure() != null ? "<" + response.describeError() + ">" : response.body();
        })).toList();
    }

    private ScanResult scanListing(Endpoint endpoint, List<Response> calls, DifferentialProbe.Result diff) {
        // recordBaseline calls twice, compare once.
        List<Response> baseline = calls.subList(0, calls.size() - 1);
        return Verdicts.listing(endpoint, baseline, calls.getLast(), diff, victim, attacker);
    }

    private ScanResult scanById(Endpoint endpoint, Target target) {
        if (target.path() == null) {
            return Verdicts.skipped(endpoint, target.skipReason());
        }
        int before = guard.findings().size();
        Response response = guard.callAs(attacker, () -> http.get(target.path(), attacker));
        List<Finding> all = guard.findings();
        List<Finding> during = List.copyOf(all.subList(Math.min(before, all.size()), all.size()));
        return Verdicts.byId(endpoint, target.path(), response, during, victim, attacker);
    }

    /** Endpoint to the first matching rule, in the order the rules were added. */
    private Map<Endpoint, String> excludedRules() {
        Map<Endpoint, String> excluded = new HashMap<>();
        for (Endpoint endpoint : endpoints) {
            exclusions.stream().filter(x -> x.matches().test(endpoint)).findFirst()
                    .ifPresent(x -> excluded.put(endpoint, x.rule()));
        }
        return excluded;
    }

    private Map<String, Target> resolveTargets(List<Endpoint> scanned) {
        Map<String, Target> targets = new LinkedHashMap<>();
        scanned.stream().filter(Endpoint::hasPathVariables).forEach(e -> targets.put(e.pathTemplate(), target(e)));
        return targets;
    }

    private Target target(Endpoint endpoint) {
        Map<String, String> encoded = new HashMap<>();
        for (String variable : endpoint.pathVariables()) {
            Resolution resolution = resolve(endpoint.pathTemplate(), variable);
            if (!resolution.isResolved()) {
                return new Target(null, resolution.reason());
            }
            encoded.put(variable, UriUtils.encodePathSegment(resolution.id(), StandardCharsets.UTF_8));
        }
        String path = EndpointDiscovery.VARIABLE.matcher(endpoint.pathTemplate())
                .replaceAll(m -> Matcher.quoteReplacement(encoded.get(m.group(1))));
        return new Target(path, null);
    }

    /** A broken user resolver must not abort the scan; it turns into a SKIPPED row with the reason. */
    private Resolution resolve(String template, String variable) {
        try {
            Resolution resolution = resourceIds.resolve(template, variable, victim);
            return resolution != null ? resolution : Resolution.unresolved("resolver returned null for {" + variable + "}");
        } catch (RuntimeException e) {
            return Resolution.unresolved("id lookup for {%s} failed: %s: %s"
                    .formatted(variable, e.getClass().getSimpleName(), e.getMessage()));
        }
    }

    public static final class Builder {

        private final MockMvc mockMvc;
        private final TenantGuard guard;
        private List<Endpoint> endpoints;
        private final List<Exclusion> exclusions = new ArrayList<>();
        private TenantSeeder seeder;
        private TenantAuthenticator authenticator;
        private ResourceIds resourceIds = ResourceIds.builder().build();
        private String victim = "A";
        private String attacker = "B";
        private ObjectMapper objectMapper = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        private DataSource writeDataSource;
        private String writeTenantColumn;
        private final Map<String, Function<String, String>> bodies = new LinkedHashMap<>();
        private RequestBodies requestBodies;
        private final List<String> spoofFields = new ArrayList<>();
        private boolean spoof;
        private Runnable reset;

        private Builder(MockMvc mockMvc, TenantGuard guard) {
            this.mockMvc = Objects.requireNonNull(mockMvc, "mockMvc");
            this.guard = Objects.requireNonNull(guard, "guard");
        }

        /**
         * Usually {@link EndpointDiscovery#discover}, plus {@link EndpointDiscovery#discoverWrites} with
         * {@link #includeWrites}; method and template pairs must be unique.
         */
        public Builder endpoints(List<Endpoint> endpoints) {
            List<Endpoint> copy = List.copyOf(endpoints);
            if (!copy.stream().map(Endpoint::label).allMatch(new HashSet<>()::add)) {
                throw new IllegalArgumentException("Duplicate endpoints in " + copy);
            }
            this.endpoints = copy;
            return this;
        }

        /**
         * Skips endpoints whose path template matches any of the Ant patterns (Spring's
         * {@code AntPathMatcher}): {@code /api/v1/tenants/**} covers {@code /api/v1/tenants} and
         * {@code /api/v1/tenants/{id}}. For endpoints the attacker may legitimately use across tenants,
         * e.g. platform-admin screens when the attacker's token carries platform permissions.
         */
        public Builder exclude(String... antPatterns) {
            List<String> patterns = List.of(antPatterns);
            patterns.stream().filter(p -> p.isBlank() || !p.startsWith("/")).findFirst().ifPresent(p -> {
                throw new IllegalArgumentException("Exclusion pattern must start with '/': '" + p + "'");
            });
            patterns.forEach(p -> exclusions.add(new Exclusion(p, e -> TEMPLATES.match(p, e.pathTemplate()))));
            return this;
        }

        /** Like {@link #excludeIf(String, Predicate)}; the report names the rule "excludeIf predicate". */
        public Builder excludeIf(Predicate<Endpoint> predicate) {
            return excludeIf("excludeIf predicate", predicate);
        }

        /** {@code rule} is printed in the report ("excluded by rule TENANT_MANAGE only"), so say why. */
        public Builder excludeIf(String rule, Predicate<Endpoint> predicate) {
            if (rule == null || rule.isBlank()) {
                throw new IllegalArgumentException("Exclusion rule needs a description for the report");
            }
            exclusions.add(new Exclusion(rule.strip(), Objects.requireNonNull(predicate, "predicate")));
            return this;
        }

        public Builder seeder(TenantSeeder seeder) {
            this.seeder = Objects.requireNonNull(seeder, "seeder");
            return this;
        }

        public Builder authenticator(TenantAuthenticator authenticator) {
            this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
            return this;
        }

        /** Default resolves nothing, so every endpoint with a path variable is SKIPPED. */
        public Builder resourceIds(ResourceIds resourceIds) {
            this.resourceIds = Objects.requireNonNull(resourceIds, "resourceIds");
            return this;
        }

        public Builder tenants(String victim, String attacker) {
            if (victim == null || attacker == null || victim.isBlank() || victim.equals(attacker)) {
                throw new IllegalArgumentException("Need two distinct tenants, got victim=%s attacker=%s"
                        .formatted(victim, attacker));
            }
            this.victim = victim;
            this.attacker = attacker;
            return this;
        }

        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
            return this;
        }

        /**
         * Also scans the POST, PUT, PATCH and DELETE endpoints passed to {@link #endpoints}. Off by
         * default because writes mutate data: before each write that follows a write which changed
         * something, both tenants' rows are deleted from every table with {@code tenantColumn} and
         * re-seeded. {@code dataSource} is read and written as the owner (guard inactive), so the
         * app's guarded DataSource works.
         */
        public Builder includeWrites(DataSource dataSource, String tenantColumn) {
            this.writeDataSource = Objects.requireNonNull(dataSource, "dataSource");
            if (tenantColumn == null || tenantColumn.isBlank()) {
                throw new IllegalArgumentException("includeWrites needs the tenant column");
            }
            this.writeTenantColumn = tenantColumn;
            return this;
        }

        /**
         * The JSON body for one write endpoint, {@code "PUT /invoices/{id}"}; {@code jsonForTenant}
         * receives the sending tenant (the attacker). Sent exactly as written: a tenant field in it is
         * the user's choice. Overrides the generated body.
         */
        public Builder body(String endpoint, Function<String, String> jsonForTenant) {
            if (endpoint == null || !WRITE_LABEL.matcher(endpoint).matches()) {
                throw new IllegalArgumentException("Expected \"POST|PUT|PATCH|DELETE /path\", got: " + endpoint);
            }
            bodies.put(endpoint, Objects.requireNonNull(jsonForTenant, "jsonForTenant"));
            return this;
        }

        /** Replaces the default {@link RequestBodies#introspecting} fallback behind {@link #body}. */
        public Builder requestBodies(RequestBodies fallback) {
            this.requestBodies = Objects.requireNonNull(fallback, "fallback");
            return this;
        }

        /**
         * Opt-in tenant spoofing probe for POST: a second call whose body sets every top-level tenant
         * field ({@code tenantId}, the tenant column in any spelling, and {@code fieldNames}) to the
         * victim's id. A row created for the victim is a LEAK. Without it, generated bodies carry no
         * tenant field at all.
         */
        public Builder spoofTenantField(String... fieldNames) {
            for (String name : fieldNames) {
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException("Blank spoof field name");
                }
            }
            spoofFields.addAll(List.of(fieldNames));
            spoof = true;
            return this;
        }

        /**
         * Replaces "delete both tenants' rows" before a re-seed, e.g. when tables without the tenant
         * column reference tenant rows. Runs as the owner; the seeder runs after it.
         */
        public Builder resetWith(Runnable reset) {
            this.reset = Objects.requireNonNull(reset, "reset");
            return this;
        }

        public EndpointScanner build() {
            require(seeder, "seeder");
            require(authenticator, "authenticator");
            require(endpoints, "endpoints (e.g. EndpointDiscovery.discover(context))");
            List<String> writes = endpoints.stream().filter(Endpoint::isWrite).map(Endpoint::label).toList();
            if (!writes.isEmpty() && writeDataSource == null) {
                throw new IllegalStateException(("Write endpoints %s were passed but writes are off: they mutate data. "
                        + "Call includeWrites(dataSource, tenantColumn) or pass only GET endpoints").formatted(writes));
            }
            return new EndpointScanner(this);
        }

        private WriteScan.Settings writeSettings() {
            if (writeDataSource == null) {
                return null;
            }
            RequestBodies fallback = requestBodies != null ? requestBodies : RequestBodies.introspecting(objectMapper);
            return new WriteScan.Settings(writeDataSource, writeTenantColumn, WriteScan.withOverrides(bodies, fallback),
                    new TenantFields(objectMapper, writeTenantColumn, spoofFields, spoof), reset);
        }

        private static void require(Object value, String name) {
            if (value == null) {
                throw new IllegalStateException("EndpointScanner needs " + name);
            }
        }
    }
}
