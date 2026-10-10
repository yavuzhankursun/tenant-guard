package dev.tenantguard;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/** Wraps every statement a connection creates so its notices reach the guard. */
final class ObservingConnectionHandler implements InvocationHandler {

    private final Connection target;
    private final TenantGuard guard;

    private ObservingConnectionHandler(Connection target, TenantGuard guard) {
        this.target = target;
        this.guard = guard;
    }

    static Connection wrap(Connection target, TenantGuard guard) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                new ObservingConnectionHandler(target, guard));
    }

    /**
     * Whether {@code connection} is, or wraps, an observing proxy: a guard under a proxy that was itself
     * guarded (a routing DataSource over guarded pool beans) already set the role and observes it.
     */
    static boolean isObserved(Connection connection) throws SQLException {
        return connection.isWrapperFor(ObservingConnectionHandler.class);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("isWrapperFor".equals(method.getName()) && args[0] == ObservingConnectionHandler.class) {
            return true;
        }
        // unwrap(Connection.class) would otherwise hand out the raw driver connection, whose statements
        // nobody observes. Driver-specific types (PgConnection) still get the raw object: a known blind spot.
        if ("unwrap".equals(method.getName()) && ((Class<?>) args[0]).isInstance(proxy)) {
            return proxy;
        }
        Object result;
        try {
            result = method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
        return switch (method.getName()) {
            case "createStatement" -> wrapStatement(Statement.class, (Statement) result, (Connection) proxy, null);
            case "prepareStatement" ->
                    wrapStatement(PreparedStatement.class, (Statement) result, (Connection) proxy, (String) args[0]);
            case "prepareCall" ->
                    wrapStatement(CallableStatement.class, (Statement) result, (Connection) proxy, (String) args[0]);
            default -> result;
        };
    }

    private Object wrapStatement(Class<? extends Statement> type, Statement statement, Connection owner, String sql) {
        return Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                new ObservingStatementHandler(statement, owner, guard, sql));
    }
}
