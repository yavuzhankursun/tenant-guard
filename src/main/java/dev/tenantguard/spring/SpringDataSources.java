package dev.tenantguard.spring;

import dev.tenantguard.GuardedDataSource;
import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.util.ReflectionUtils;

/**
 * Where the guard goes in a chain of Spring's DataSource wrappers, and what an
 * {@code AbstractRoutingDataSource} routes to. Internal: used by {@link TenantGuardConfiguration} and
 * the JUnit extension. spring-jdbc is not a dependency of the library, so its types are matched by
 * class name and read through bean properties.
 *
 * <p>Order rule: the guard sits directly on the pool, under {@code LazyConnectionDataSourceProxy} and
 * {@code TransactionAwareDataSourceProxy}. Above a {@code TransactionAwareDataSourceProxy} it would
 * become the transaction manager's binding key, while the proxy looks connections up by its own
 * target, so raw {@code getConnection()} calls would leave the transaction; above a lazy proxy it
 * would fetch the physical connection at once and bypass the proxy's read-only target. A routing
 * DataSource is guarded as a whole, so every target is covered, including targets added later.
 */
public final class SpringDataSources {

    static final String DELEGATING = "org.springframework.jdbc.datasource.DelegatingDataSource";
    static final String LAZY = "org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy";
    static final String ROUTING = "org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource";

    private SpringDataSources() {
    }

    /**
     * Guards a DataSource bean: returns {@code bean} itself when a guard already sits under it or was
     * placed under its Spring proxies, otherwise {@code wrap.apply(bean)}.
     */
    public static DataSource guard(DataSource bean, UnaryOperator<DataSource> wrap) {
        if (!needsGuard(bean)) {
            return bean;
        }
        if (!is(bean, DELEGATING)) {
            return wrap.apply(bean);
        }
        BeanWrapper proxy = PropertyAccessorFactory.forBeanPropertyAccess(bean);
        DataSource target = (DataSource) proxy.getPropertyValue("targetDataSource");
        if (target == null) {
            return wrap.apply(bean);
        }
        DataSource guarded = guard(target, wrap);
        if (guarded != target) {
            proxy.setPropertyValue("targetDataSource", guarded);
        }
        if (is(bean, LAZY)) {
            guardReadOnlyTarget(bean, wrap);
        }
        return bean;
    }

    /** Spring 6.1.2+: read-only transactions borrow from a second target, a replica pool that is often not a bean. */
    private static void guardReadOnlyTarget(DataSource lazy, UnaryOperator<DataSource> wrap) {
        Field field = ReflectionUtils.findField(lazy.getClass(), "readOnlyDataSource", DataSource.class);
        if (field == null) {
            return;
        }
        ReflectionUtils.makeAccessible(field);
        DataSource readOnly = (DataSource) ReflectionUtils.getField(field, lazy);
        if (readOnly != null) {
            DataSource guarded = guard(readOnly, wrap);
            if (guarded != readOnly) {
                PropertyAccessorFactory.forBeanPropertyAccess(lazy).setPropertyValue("readOnlyDataSource", guarded);
            }
        }
    }

    private static boolean needsGuard(DataSource dataSource) {
        if (dataSource instanceof GuardedDataSource) {
            return false;
        }
        if (is(dataSource, ROUTING) || is(dataSource, DELEGATING)) {
            return true;
        }
        // An unknown wrapper over a guarded bean. Not asked of a routing DataSource: it would answer
        // for whichever target the current lookup key picks, not for all of them.
        try {
            return !dataSource.isWrapperFor(GuardedDataSource.class);
        } catch (SQLException | RuntimeException e) {
            return true;
        }
    }

    /**
     * The resolved targets of the routing DataSource under {@code dataSource}, by lookup key, the
     * default target last under {@code "(default)"} unless it is one of the keyed ones. Empty when
     * {@code dataSource} does not route.
     */
    public static Map<String, DataSource> routingTargets(DataSource dataSource) {
        Object routing = routing(dataSource);
        if (routing == null) {
            return Map.of();
        }
        BeanWrapper router = PropertyAccessorFactory.forBeanPropertyAccess(routing);
        Map<?, ?> resolved;
        try {
            resolved = (Map<?, ?>) router.getPropertyValue("resolvedDataSources");
        } catch (RuntimeException e) {
            throw new IllegalStateException(("TenantGuard could not read the targets of routing DataSource %s: %s")
                    .formatted(routing.getClass().getName(), e.getMessage()), e);
        }
        Map<String, DataSource> targets = new LinkedHashMap<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        resolved.forEach((key, target) -> {
            targets.put(String.valueOf(key), (DataSource) target);
            seen.add(target);
        });
        DataSource fallback = (DataSource) router.getPropertyValue("resolvedDefaultDataSource");
        if (fallback != null && !seen.contains(fallback)) {
            targets.put("(default)", fallback);
        }
        return targets;
    }

    private static Object routing(DataSource dataSource) {
        if (is(dataSource, ROUTING)) {
            return dataSource;
        }
        // A guard or Spring proxy over the router delegates unwrap; routing itself only after its own type.
        try {
            Class<?> type = Class.forName(ROUTING, false, dataSource.getClass().getClassLoader());
            return dataSource.isWrapperFor(type) ? dataSource.unwrap(type) : null;
        } catch (ClassNotFoundException | SQLException | RuntimeException e) {
            return null;
        }
    }

    private static boolean is(Object object, String className) {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals(className)) {
                return true;
            }
        }
        return false;
    }
}
