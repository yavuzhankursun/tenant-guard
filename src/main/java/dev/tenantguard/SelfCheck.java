package dev.tenantguard;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * Fails loudly when the guard would be blind. Finding nothing because RLS is bypassed looks exactly
 * like a clean result, which is the worst failure mode this tool can have.
 */
public final class SelfCheck {

    private SelfCheck() {
    }

    public static void verify(DataSource guarded, TenantGuard guard) throws SQLException {
        if (!guard.isActive()) {
            throw new IllegalStateException("TenantGuard self-check needs an active guard");
        }
        try (Connection connection = guarded.getConnection(); Statement statement = connection.createStatement()) {
            requireRlsApplies(statement);
            requireProbeObserved(statement, guard);
        }
    }

    private static void requireRlsApplies(Statement statement) throws SQLException {
        String sql = "SELECT current_user, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user";
        try (ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) {
                throw new IllegalStateException("TenantGuard is blind: current role not found in pg_roles");
            }
            if (rs.getBoolean(2) || rs.getBoolean(3)) {
                throw new IllegalStateException(("TenantGuard is blind: role '%s' bypasses row level security "
                        + "(superuser=%s, bypassrls=%s)").formatted(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3)));
            }
        }
    }

    private static void requireProbeObserved(Statement statement, TenantGuard guard) throws SQLException {
        try (ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + ObserverInstaller.PROBE_TABLE)) {
            rs.next();
        }
        boolean observed = guard.observations().stream()
                .anyMatch(o -> o.tenantsByTable().containsKey(ObserverInstaller.PROBE_TABLE));
        if (!observed) {
            throw new IllegalStateException("TenantGuard is blind: probe query produced no observer notice "
                    + "(policy missing, or client_min_messages filters NOTICE)");
        }
    }
}
