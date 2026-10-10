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
 * as EXCLUDED with the rule.
 */
public final class EndpointScanner {

    private record Target(String path, String skipReason) {
    }

    /** {@code rule} is what the report prints: the Ant pattern or the user's description. */
    private record Exclusion(String rule, Predicate<Endpoint> matches) {
    }

    private static final AntPathMatcher TEMPLATES = new AntPathMatcher();

    private final TenantGuard guard;
    private final TenantSeeder seeder;
    private final ResourceIds resourceIds;
    private final HttpCaller http;
    private final List<Endpoint> endpoints;
    private final List<Exclusion> exclusions;
    private final String victim;
    private final String attacker;

    private EndpointScanner(Builder builder) {
        this.guard = builder.guard;
        this.seeder = builder.seeder;
        this.resourceIds = builder.resourceIds;
        this.http = new HttpCaller(builder.mockMvc, builder.authenticator, builder.objectMapper);
        this.endpoints = builder.endpoints;
        this.exclusions = List.copyOf(builder.exclusions);
        this.victim = builder.victim;
        this.attacker = builder.attacker;
    }

    public static Builder builder(MockMvc mockMvc, TenantGuard guard) {
        return new Builder(mockMvc, guard);
    }

    /** Leaves the guard in the state it found it; the tables keep both tenants' data afterwards. */
    public ScanReport scan() {
        boolean wasActive = guard.isActive();
        try {
            Map<String, String> excluded = excludedRules();
            List<Endpoint> scanned = endpoints.stream().filter(e -> !excluded.containsKey(e.pathTemplate())).toList();
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
            List<ScanResult> results = endpoints.stream()
                    .map(e -> excluded.containsKey(e.pathTemplate()) ? Verdicts.excluded(e, excluded.get(e.pathTemplate()))
                            : e.hasPathVariables() ? scanById(e, targets.get(e.pathTemplate()))
                            : scanListing(e, responses.get(e.pathTemplate()), diffs.get(e.pathTemplate())))
                    .toList();
            return new ScanReport(victim, attacker, results);
        } finally {
            if (wasActive) {
                guard.activate();
            } else {
                guard.deactivate();
            }
        }
    }

    /** Seeding and id lookups write/read as the owner: an observed seeder would drown the real findings. */
    private <T> T asOwner(Supplier<T> action) {
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

    /** Template to the first matching rule, in the order the rules were added. */
    private Map<String, String> excludedRules() {
        Map<String, String> excluded = new HashMap<>();
        for (Endpoint endpoint : endpoints) {
            exclusions.stream().filter(x -> x.matches().test(endpoint)).findFirst()
                    .ifPresent(x -> excluded.put(endpoint.pathTemplate(), x.rule()));
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

        private Builder(MockMvc mockMvc, TenantGuard guard) {
            this.mockMvc = Objects.requireNonNull(mockMvc, "mockMvc");
            this.guard = Objects.requireNonNull(guard, "guard");
        }

        /** Usually {@link EndpointDiscovery#discover}; templates must be unique. */
        public Builder endpoints(List<Endpoint> endpoints) {
            List<Endpoint> copy = List.copyOf(endpoints);
            if (!copy.stream().map(Endpoint::pathTemplate).allMatch(new HashSet<>()::add)) {
                throw new IllegalArgumentException("Duplicate path templates in " + copy);
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

        public EndpointScanner build() {
            require(seeder, "seeder");
            require(authenticator, "authenticator");
            require(endpoints, "endpoints (e.g. EndpointDiscovery.discover(context))");
            return new EndpointScanner(this);
        }

        private static void require(Object value, String name) {
            if (value == null) {
                throw new IllegalStateException("EndpointScanner needs " + name);
            }
        }
    }
}
