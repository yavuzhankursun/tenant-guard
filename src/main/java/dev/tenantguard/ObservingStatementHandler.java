package dev.tenantguard;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Collects observer notices from a statement's warning chain. Harvests right after execute, and
 * again before clearWarnings/close so an ORM that logs-and-clears warnings cannot swallow them.
 * The chain itself is left untouched for the application. Result sets are wrapped as well, because
 * notices from later cursor fetches land on them.
 */
final class ObservingStatementHandler implements InvocationHandler {

    private final Statement target;
    private final Connection owner;
    private final TenantGuard guard;
    private final WarningCursor cursor = new WarningCursor();
    private String sql;
    private ResultSet driverResult;
    private ResultSet observedResult;

    ObservingStatementHandler(Statement target, Connection owner, TenantGuard guard, String sql) {
        this.target = target;
        this.owner = owner;
        this.guard = guard;
        this.sql = sql;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        switch (name) {
            // Hand back the observing connection, or statements created from it would go unwatched.
            case "getConnection" -> {
                return owner;
            }
            case "getResultSet" -> {
                return wrapResultSet(call(method, args), (Statement) proxy);
            }
            // unwrap(Statement.class) would otherwise hand out the raw driver statement, which nobody observes.
            case "unwrap" -> {
                return ((Class<?>) args[0]).isInstance(proxy) ? proxy : call(method, args);
            }
            case "clearWarnings" -> {
                harvest(TenantGuard.HarvestPoint.CLEAR_WARNINGS);
                return call(method, args);
            }
            case "close" -> {
                if (!target.isClosed()) {
                    harvest(TenantGuard.HarvestPoint.CLOSE);
                }
                return call(method, args);
            }
            default -> {
                if (!name.startsWith("execute")) {
                    return call(method, args);
                }
                // pgjdbc clears the chain itself on every execute, bypassing our clearWarnings hook.
                harvest(TenantGuard.HarvestPoint.CLEAR_WARNINGS);
                if (args != null && args.length > 0 && args[0] instanceof String executed) {
                    sql = executed;
                }
                Object result = call(method, args);
                harvest(TenantGuard.HarvestPoint.EXECUTE);
                return wrapResultSet(result, (Statement) proxy);
            }
        }
    }

    /**
     * Pools hand out a fresh wrapper per call (Hikari: executeQuery and getResultSet), so one driver
     * ResultSet can arrive twice. One observing proxy per driver result keeps one cursor over its
     * chain; two would count every later-fetch notice twice.
     */
    private Object wrapResultSet(Object result, Statement proxy) {
        if (!(result instanceof ResultSet rs)) {
            return result;
        }
        ResultSet driver = driverOf(rs);
        if (driver != driverResult) {
            driverResult = driver;
            observedResult = ObservingResultSetHandler.wrap(rs, proxy, guard, sql);
        }
        return observedResult;
    }

    /** A wrapper that cannot unwrap only loses the de-duplication; observing must never fail the query. */
    private static ResultSet driverOf(ResultSet rs) {
        try {
            return rs.unwrap(ResultSet.class);
        } catch (SQLException e) {
            return rs;
        }
    }

    private void harvest(TenantGuard.HarvestPoint point) throws SQLException {
        guard.record(sql, cursor.drain(target.getWarnings()), point);
    }

    private Object call(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
