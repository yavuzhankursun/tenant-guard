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
 *
 * <p>Statement caches (c3p0 {@code maxStatements}, DBCP2 {@code poolPreparedStatements}, tomcat-jdbc
 * {@code StatementCache}, vibur) hand the next borrower the same driver statement with its chain
 * intact. The previous proxy already harvested that chain, so a new proxy starts behind it.
 */
final class ObservingStatementHandler implements InvocationHandler {

    private final Statement target;
    private final Connection owner;
    private final TenantGuard guard;
    private final WarningCursor cursor = new WarningCursor();
    private String sql;
    /** The proxy over the statement's current result; null until one is seen after an execute. */
    private ResultSet observedResult;

    ObservingStatementHandler(Statement target, Connection owner, TenantGuard guard, String sql) {
        this.target = target;
        this.owner = owner;
        this.guard = guard;
        this.sql = sql;
        skipLeftovers();
    }

    /** Nothing a fresh driver statement carries; a cached one carries the previous borrower's notices. */
    private void skipLeftovers() {
        try {
            cursor.drain(target.getWarnings());
        } catch (SQLException e) {
            // A wrapper that cannot report warnings yet has none left over; observing must never fail the app.
        }
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
                return currentResult(call(method, args), (Statement) proxy);
            }
            case "getMoreResults" -> {
                observedResult = null;
                return call(method, args);
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
                observedResult = null;
                Object result = call(method, args);
                harvest(TenantGuard.HarvestPoint.EXECUTE);
                return currentResult(result, (Statement) proxy);
            }
        }
    }

    /**
     * Pools hand out a fresh wrapper per call (Hikari, DBCP2: executeQuery and getResultSet), so one
     * driver ResultSet can arrive twice, and DBCP2's {@code unwrap} returns the wrapper itself, so
     * identity cannot tell. JDBC can: until the next execute or getMoreResults, getResultSet returns
     * the execute's result. One proxy per current result keeps one cursor over its chain; two would
     * count every later-fetch notice twice.
     */
    private Object currentResult(Object result, Statement proxy) {
        if (!(result instanceof ResultSet rs)) {
            return result;
        }
        if (observedResult == null) {
            observedResult = ObservingResultSetHandler.wrap(rs, proxy, guard, sql);
        }
        return observedResult;
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
