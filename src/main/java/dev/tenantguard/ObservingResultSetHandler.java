package dev.tenantguard;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

/**
 * pgjdbc attaches notices from later cursor fetches (fetchSize set, autocommit off) to the
 * ResultSet, not the Statement. Without this wrapper every row after the first batch goes unseen.
 */
final class ObservingResultSetHandler implements InvocationHandler {

    private static final Set<String> NAVIGATION = Set.of(
            "next", "previous", "first", "last", "absolute", "relative", "beforeFirst", "afterLast");

    private final ResultSet target;
    private final Statement owner;
    private final TenantGuard guard;
    private final String sql;
    private final WarningCursor cursor = new WarningCursor();

    private ObservingResultSetHandler(ResultSet target, Statement owner, TenantGuard guard, String sql) {
        this.target = target;
        this.owner = owner;
        this.guard = guard;
        this.sql = sql;
    }

    static ResultSet wrap(ResultSet target, Statement owner, TenantGuard guard, String sql) {
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                new ObservingResultSetHandler(target, owner, guard, sql));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        switch (name) {
            case "getStatement" -> {
                return owner;
            }
            case "clearWarnings" -> {
                harvest();
                Object result = call(method, args);
                cursor.reset();
                return result;
            }
            case "close" -> {
                if (!target.isClosed()) {
                    harvest();
                }
                return call(method, args);
            }
            default -> {
                Object result = call(method, args);
                if (NAVIGATION.contains(name)) {
                    harvest();
                }
                return result;
            }
        }
    }

    private void harvest() throws SQLException {
        guard.record(sql, cursor.drain(target.getWarnings()), TenantGuard.HarvestPoint.FETCH);
    }

    private Object call(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
