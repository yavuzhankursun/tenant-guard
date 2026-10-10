package dev.tenantguard.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Lists GET endpoints straight from Spring MVC's handler mappings: no OpenAPI spec to keep in sync,
 * and every {@code @GetMapping} is found, including ones nobody documented.
 */
public final class EndpointDiscovery {

    /** Boot's error controller answers every method and reads no tenant data. */
    private static final Set<String> EXCLUDED = Set.of("/error");

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
        String handler = method.getBeanType().getSimpleName() + "#" + method.getMethod().getName();
        for (String template : info.getPatternValues()) {
            if (!EXCLUDED.contains(template)) {
                out.putIfAbsent(template, Endpoint.of(template, handler));
            }
        }
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
