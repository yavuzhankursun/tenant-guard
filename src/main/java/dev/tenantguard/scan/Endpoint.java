package dev.tenantguard.scan;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * An endpoint to scan. {@code pathVariables} must match the template: the scanner substitutes
 * exactly these, so a mismatch would send a literal {@code {id}} to the app. {@code method} is GET
 * for reads; POST, PUT, PATCH and DELETE are writes, which mutate data and are scanned only after
 * {@link EndpointScanner.Builder#includeWrites}. {@code requestBodyType} is the handler's
 * {@code @RequestBody} parameter type, or null when it takes none: the default body generator
 * builds a JSON body from it.
 */
public record Endpoint(String pathTemplate, Set<String> pathVariables, String handler,
                       RequestMethod method, Type requestBodyType) {

    private static final Set<RequestMethod> WRITES =
            EnumSet.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    public Endpoint {
        Objects.requireNonNull(pathTemplate, "pathTemplate");
        Objects.requireNonNull(pathVariables, "pathVariables");
        Objects.requireNonNull(method, "method");
        if (method != RequestMethod.GET && !WRITES.contains(method)) {
            throw new IllegalArgumentException("Only GET, POST, PUT, PATCH and DELETE are scanned, not " + method);
        }
        Set<String> declared = new LinkedHashSet<>(EndpointDiscovery.pathVariables(pathTemplate));
        if (!declared.equals(Set.copyOf(pathVariables))) {
            throw new IllegalArgumentException("Path variables %s do not match template %s"
                    .formatted(pathVariables, pathTemplate));
        }
        pathVariables = Collections.unmodifiableSet(declared);
        handler = handler == null ? "" : handler;
    }

    /** A GET endpoint: the shape every scan had before writes were scanned. */
    public Endpoint(String pathTemplate, Set<String> pathVariables, String handler) {
        this(pathTemplate, pathVariables, handler, RequestMethod.GET, null);
    }

    /** GET; variables parsed from the template. */
    public static Endpoint of(String pathTemplate, String handler) {
        return of(RequestMethod.GET, pathTemplate, handler, null);
    }

    /** Variables parsed from the template; {@code requestBodyType} may be null. */
    public static Endpoint of(RequestMethod method, String pathTemplate, String handler, Type requestBodyType) {
        return new Endpoint(pathTemplate, Set.copyOf(EndpointDiscovery.pathVariables(pathTemplate)), handler,
                method, requestBodyType);
    }

    public boolean hasPathVariables() {
        return !pathVariables.isEmpty();
    }

    public boolean isWrite() {
        return WRITES.contains(method);
    }

    /** {@code PUT /invoices/{id}}: how the report prints it and how {@link ScanReport#result} finds it. */
    public String label() {
        return method.name() + " " + pathTemplate;
    }
}
