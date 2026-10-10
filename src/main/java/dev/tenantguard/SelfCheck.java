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
        try (ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + probe(statement))) {
            rs.next();
        }
        boolean observed = guard.observations().stream()
                .anyMatch(o -> o.tenantsByTable().containsKey(ObserverInstaller.PROBE_TABLE));
        if (!observed) {
            throw new IllegalStateException("TenantGuard is blind: probe query produced no observer notice "
                    + "(policy missing, or client_min_messages filters NOTICE)");
        }
    }

    /**
     * The probe lives in the installing connection's current schema, which need not be on this
     * connection's search_path. Prefer the one the bare name resolves to, else any probe carrying the
     * observer policy that this role may read. The qualified name comes back quoted by the server
     * ({@code format('%I')}) from catalog names, never from input.
     */
    private static String probe(Statement statement) throws SQLException {
        String sql = """
                SELECT format('%%I.%%I', n.nspname, c.relname)
                FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE c.relname = '%1$s' AND c.relkind = 'r'
                  AND has_schema_privilege(n.oid, 'USAGE') AND has_table_privilege(c.oid, 'SELECT')
                  AND EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid AND p.polname = '%2$s')
                ORDER BY pg_table_is_visible(c.oid) DESC, n.nspname
                LIMIT 1""".formatted(ObserverInstaller.PROBE_TABLE, ObserverInstaller.POLICY);
        try (ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) {
                throw new IllegalStateException(("TenantGuard is blind: no readable %s table with the observer "
                        + "policy (observer not installed, or role %s lacks USAGE on its schema)")
                        .formatted(ObserverInstaller.PROBE_TABLE, ObserverInstaller.ROLE));
            }
            return rs.getString(1);
        }
    }
}
