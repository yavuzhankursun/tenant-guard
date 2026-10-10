package dev.tenantguard;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * While the guard is active, every borrowed connection switches to the observed role, so the
 * application's own credentials (and Flyway's) stay untouched. While inactive it resets the role,
 * so migrations and seeders run as the owner and bypass the observer.
 */
public final class GuardedDataSource implements DataSource {

    private final DataSource delegate;
    private final TenantGuard guard;
    private final String role;

    /** @param role role to {@code SET ROLE} to while active; null keeps the connecting user (used to prove the self-check). */
    public GuardedDataSource(DataSource delegate, TenantGuard guard, String role) {
        this.delegate = delegate;
        this.guard = guard;
        this.role = role == null ? null : Identifiers.require(role);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return prepare(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return prepare(delegate.getConnection(username, password));
    }

    private Connection prepare(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sessionSetup());
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return ObservingConnectionHandler.wrap(connection, guard);
    }

    private String sessionSetup() {
        if (!guard.isActive()) {
            return "RESET ROLE";
        }
        // The app may raise client_min_messages; notices are the only channel that survives rollback and READ ONLY.
        String setRole = role == null ? "RESET ROLE" : "SET ROLE " + role;
        return setRole + "; SET client_min_messages = notice";
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        return type.isInstance(this) ? type.cast(this) : delegate.unwrap(type);
    }

    @Override
    public boolean isWrapperFor(Class<?> type) throws SQLException {
        return type.isInstance(this) || delegate.isWrapperFor(type);
    }
}
