package dev.tenantguard;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects observer notices from a statement's warning chain. Harvests right after execute, and
 * again before clearWarnings/close so an ORM that logs-and-clears warnings cannot swallow them.
 * The chain itself is left untouched for the application.
 */
final class ObservingStatementHandler implements InvocationHandler {

    private final Statement target;
    private final Connection owner;
    private final TenantGuard guard;
    private String sql;
    private int consumed;

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
            case "clearWarnings" -> {
                harvest(TenantGuard.HarvestPoint.CLEAR_WARNINGS);
                Object result = call(method, args);
                consumed = 0;
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
                consumed = 0;
                if (args != null && args.length > 0 && args[0] instanceof String executed) {
                    sql = executed;
                }
                Object result = call(method, args);
                harvest(TenantGuard.HarvestPoint.EXECUTE);
                return result;
            }
        }
    }

    private void harvest(TenantGuard.HarvestPoint point) throws SQLException {
        List<RowRead> rows = new ArrayList<>();
        int index = 0;
        for (SQLWarning w = target.getWarnings(); w != null; w = w.getNextWarning(), index++) {
            if (index >= consumed) {
                RowRead.parse(w).ifPresent(rows::add);
            }
        }
        consumed = index;
        guard.record(sql, rows, point);
    }

    private Object call(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
