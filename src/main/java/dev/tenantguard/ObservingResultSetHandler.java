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
            // unwrap(ResultSet.class) would otherwise hand out the raw result, whose later fetches nobody observes.
            case "unwrap" -> {
                return ((Class<?>) args[0]).isInstance(proxy) ? proxy : call(method, args);
            }
            case "clearWarnings" -> {
                harvest();
                return call(method, args);
            }
            case "close" -> {
                harvestBeforeClose();
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

    /**
     * Re-executing the statement closes this result in the driver, yet c3p0's wrapper still reports
     * {@code isClosed() == false} and then throws from getWarnings. Its rows were harvested on
     * navigation, so an unreadable chain at close loses nothing; failing the app's close would.
     */
    private void harvestBeforeClose() {
        try {
            if (!target.isClosed()) {
                harvest();
            }
        } catch (SQLException e) {
            // Closed underneath us; see above.
        }
    }

    private void harvest() throws SQLException {
        guard.record(sql, cursor.drain(target.getWarnings(), guard::trusts), TenantGuard.HarvestPoint.FETCH);
    }

    private Object call(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
