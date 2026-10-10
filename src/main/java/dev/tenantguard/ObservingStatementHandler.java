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
            case "clearWarnings" -> {
                harvest(TenantGuard.HarvestPoint.CLEAR_WARNINGS);
                Object result = call(method, args);
                cursor.reset();
                return result;
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
                cursor.reset();
                if (args != null && args.length > 0 && args[0] instanceof String executed) {
                    sql = executed;
                }
                Object result = call(method, args);
                harvest(TenantGuard.HarvestPoint.EXECUTE);
                return wrapResultSet(result, (Statement) proxy);
            }
        }
    }

    private Object wrapResultSet(Object result, Statement proxy) {
        return result instanceof ResultSet rs ? ObservingResultSetHandler.wrap(rs, proxy, guard, sql) : result;
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
