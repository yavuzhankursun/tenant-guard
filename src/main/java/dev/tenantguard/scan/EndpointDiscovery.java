package dev.tenantguard.scan;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Lists endpoints straight from Spring MVC's handler mappings: no OpenAPI spec to keep in sync, and
 * every {@code @GetMapping} is found, including ones nobody documented. Writes are listed by a
 * separate method so that existing callers of {@link #discover} never start mutating data.
 */
public final class EndpointDiscovery {

    /** Boot's error controller answers every method and reads no tenant data. */
    private static final Set<String> EXCLUDED = Set.of("/error");

    private static final Set<RequestMethod> WRITES =
            Set.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    /** {@code {id}}, {@code {id:\d+}} and {@code {*rest}}; group 1 is the name. */
    static final Pattern VARIABLE = Pattern.compile("\\{\\*?([^}:]+)(?::[^}]*)?}");

    private EndpointDiscovery() {
    }

    /** Sorted by path template; one entry per template even if several handlers share it. */
    public static List<Endpoint> discover(ApplicationContext context) {
        Map<String, Endpoint> byTemplate = new TreeMap<>();
        BeanFactoryUtils.beansOfTypeIncludingAncestors(context, RequestMappingHandlerMapping.class)
                .values()
                .forEach(mapping -> mapping.getHandlerMethods().forEach((info, method) -> collect(info, method, byTemplate)));
        return List.copyOf(byTemplate.values());
    }

    private static void collect(RequestMappingInfo info, HandlerMethod method, Map<String, Endpoint> out) {
        Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
        // No method condition means the handler answers GET too.
        if (!methods.isEmpty() && !methods.contains(RequestMethod.GET)) {
            return;
        }
        String handler = handlerName(method);
        for (String template : info.getPatternValues()) {
            if (!EXCLUDED.contains(template)) {
                out.putIfAbsent(template, Endpoint.of(template, handler));
            }
        }
    }

    /**
     * POST, PUT, PATCH and DELETE endpoints, sorted by template and then in that method order; one
     * entry per method and template. Only handlers that name the method: one without a method
     * condition answers every method, and sending it a DELETE would be guesswork.
     */
    public static List<Endpoint> discoverWrites(ApplicationContext context) {
        Map<String, Endpoint> byLabel = new LinkedHashMap<>();
        BeanFactoryUtils.beansOfTypeIncludingAncestors(context, RequestMappingHandlerMapping.class)
                .values()
                .forEach(mapping -> mapping.getHandlerMethods().forEach((info, method) -> collectWrites(info, method, byLabel)));
        return byLabel.values().stream()
                .sorted(Comparator.comparing(Endpoint::pathTemplate).thenComparing(Endpoint::method))
                .toList();
    }

    private static void collectWrites(RequestMappingInfo info, HandlerMethod method, Map<String, Endpoint> out) {
        Type body = requestBodyType(method);
        for (RequestMethod verb : info.getMethodsCondition().getMethods()) {
            if (!WRITES.contains(verb)) {
                continue;
            }
            for (String template : info.getPatternValues()) {
                if (!EXCLUDED.contains(template)) {
                    Endpoint endpoint = Endpoint.of(verb, template, handlerName(method), body);
                    out.putIfAbsent(endpoint.label(), endpoint);
                }
            }
        }
    }

    /** The generic type, so {@code List<LineDto>} stays a list of line DTOs for the body generator. */
    private static Type requestBodyType(HandlerMethod method) {
        return Arrays.stream(method.getMethodParameters())
                .filter(p -> p.hasParameterAnnotation(RequestBody.class))
                .findFirst()
                .map(MethodParameter::getGenericParameterType)
                .orElse(null);
    }

    private static String handlerName(HandlerMethod method) {
        return method.getBeanType().getSimpleName() + "#" + method.getMethod().getName();
    }

    /** Variable names in template order. */
    public static List<String> pathVariables(String template) {
        List<String> names = new ArrayList<>();
        Matcher matcher = VARIABLE.matcher(template);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return List.copyOf(names);
    }
}
