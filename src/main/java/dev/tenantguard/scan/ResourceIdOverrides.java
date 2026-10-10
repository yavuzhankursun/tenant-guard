package dev.tenantguard.scan;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * User-supplied ids per template and variable, for paths no table guess can map
 * ({@code /me/documents/{slug}}, composite keys, parent/child ids that must match).
 */
public final class ResourceIdOverrides implements ResourceIds {

    private record Key(String template, String variable) {
    }

    private final Map<Key, Function<String, ?>> overrides;
    private final ResourceIds fallback;

    private ResourceIdOverrides(Map<Key, Function<String, ?>> overrides, ResourceIds fallback) {
        this.overrides = Map.copyOf(overrides);
        this.fallback = fallback;
    }

    @Override
    public Resolution resolve(String pathTemplate, String variable, String tenant) {
        Function<String, ?> override = overrides.get(new Key(pathTemplate, variable));
        if (override != null) {
            Object id = override.apply(tenant);
            return id == null
                    ? Resolution.unresolved("override for {%s} returned no id for tenant %s".formatted(variable, tenant))
                    : Resolution.of(id);
        }
        if (fallback == null) {
            return Resolution.unresolved(("no id source for {%s}: add ResourceIds.builder().id(\"%s\", \"%s\", ...)"
                    + " or a fallback").formatted(variable, pathTemplate, variable));
        }
        return fallback.resolve(pathTemplate, variable, tenant);
    }

    public static final class Builder {

        private final Map<Key, Function<String, ?>> overrides = new LinkedHashMap<>();
        private ResourceIds fallback;

        Builder() {
        }

        /** {@code idForTenant} receives the tenant and returns one of its ids; null means none. */
        public Builder id(String pathTemplate, String variable, Function<String, ?> idForTenant) {
            if (!EndpointDiscovery.pathVariables(pathTemplate).contains(variable)) {
                throw new IllegalArgumentException("Template %s has no variable {%s}".formatted(pathTemplate, variable));
            }
            overrides.put(new Key(pathTemplate, variable), Objects.requireNonNull(idForTenant, "idForTenant"));
            return this;
        }

        public Builder fallback(ResourceIds fallback) {
            this.fallback = Objects.requireNonNull(fallback, "fallback");
            return this;
        }

        public ResourceIdOverrides build() {
            return new ResourceIdOverrides(overrides, fallback);
        }
    }
}
