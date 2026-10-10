package dev.tenantguard.scan;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A GET endpoint to scan. {@code pathVariables} must match the template: the scanner substitutes
 * exactly these, so a mismatch would send a literal {@code {id}} to the app.
 */
public record Endpoint(String pathTemplate, Set<String> pathVariables, String handler) {

    public Endpoint {
        Objects.requireNonNull(pathTemplate, "pathTemplate");
        Objects.requireNonNull(pathVariables, "pathVariables");
        Set<String> declared = new LinkedHashSet<>(EndpointDiscovery.pathVariables(pathTemplate));
        if (!declared.equals(Set.copyOf(pathVariables))) {
            throw new IllegalArgumentException("Path variables %s do not match template %s"
                    .formatted(pathVariables, pathTemplate));
        }
        pathVariables = Collections.unmodifiableSet(declared);
        handler = handler == null ? "" : handler;
    }

    /** Variables parsed from the template. */
    public static Endpoint of(String pathTemplate, String handler) {
        return new Endpoint(pathTemplate, Set.copyOf(EndpointDiscovery.pathVariables(pathTemplate)), handler);
    }

    public boolean hasPathVariables() {
        return !pathVariables.isEmpty();
    }
}
