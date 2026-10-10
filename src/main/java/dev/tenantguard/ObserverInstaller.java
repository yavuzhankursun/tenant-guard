package dev.tenantguard;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Installs the observer: a non-owner, non-bypass role and a policy that always returns true but
 * raises a NOTICE for every row the executor reads. No FORCE ROW LEVEL SECURITY: the owner
 * (migrations, seeder) must bypass the observer, only the role the app switches into is watched.
 * Must run as the table owner or a superuser, after the schema exists.
 *
 * <p>On a table that already has its own permissive policy the observer is created
 * {@code AS RESTRICTIVE}: Postgres ORs permissive policies, so a permissive always-true observer
 * would switch the app's isolation off, while a restrictive one is AND-ed and leaves it intact.
 * Rows the app's policy rejects are then never observed, which is right: they did not leak.
 */
public final class ObserverInstaller {

    public static final String ROLE = "tg_app";
    public static final String PROBE_TABLE = "tg_probe";
    static final String POLICY = "tg_observer";

    private ObserverInstaller() {
    }

    public static void install(Connection owner, String tenantColumn, List<String> tables) throws SQLException {
        String column = Identifiers.require(tenantColumn);
        List<String> observed = tables.stream().map(Identifiers::require).toList();
        Map<String, Boolean> restrictive = new LinkedHashMap<>();
        for (String table : observed) {
            restrictive.put(table, policyKind(owner, table) == PolicyKind.RESTRICTIVE);
        }

        List<String> ddl = new ArrayList<>(List.of(
                """
                DO $$ BEGIN
                  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%1$s') THEN
                    CREATE ROLE %1$s NOLOGIN NOSUPERUSER NOBYPASSRLS;
                  END IF;
                END $$""".formatted(ROLE),
                "GRANT " + ROLE + " TO CURRENT_USER",
                """
                CREATE OR REPLACE FUNCTION tg_observe(tbl text, row_tenant text) RETURNS boolean
                LANGUAGE plpgsql VOLATILE AS $$
                BEGIN
                  RAISE NOTICE 'tg|%|%', tbl, row_tenant;
                  RETURN true;
                END $$""",
                "CREATE TABLE IF NOT EXISTS %s (%s text NOT NULL)".formatted(PROBE_TABLE, column),
                "INSERT INTO %1$s (%2$s) SELECT 'tg-probe' WHERE NOT EXISTS (SELECT 1 FROM %1$s)"
                        .formatted(PROBE_TABLE, column),
                "GRANT USAGE ON SCHEMA public TO " + ROLE,
                "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + ROLE,
                "GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO " + ROLE));
        restrictive.forEach((table, asRestrictive) -> ddl.addAll(policyDdl(table, column, asRestrictive)));
        ddl.addAll(policyDdl(PROBE_TABLE, column, false));

        try (Statement statement = owner.createStatement()) {
            for (String sql : ddl) {
                statement.execute(sql);
            }
        }
    }

    private static List<String> policyDdl(String table, String column, boolean restrictive) {
        return List.of(
                "ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(table),
                "DROP POLICY IF EXISTS %s ON %s".formatted(POLICY, table),
                // WITH CHECK (true): the write side is out of MVP scope.
                "CREATE POLICY %s ON %s AS %s FOR ALL TO %s USING (tg_observe('%s', %s::text)) WITH CHECK (true)"
                        .formatted(POLICY, table, restrictive ? "RESTRICTIVE" : "PERMISSIVE", ROLE, table, column));
    }

    /** How the observer must be created on {@code table} given the policies already on it. */
    public enum PolicyKind { PERMISSIVE, RESTRICTIVE }

    /**
     * No foreign policy: permissive. A foreign permissive policy that applies to the observed role:
     * restrictive, so it is AND-ed. Foreign policies that never grant the role anything: refused,
     * because a restrictive-only table denies every row and the observer would see nothing.
     */
    public static PolicyKind policyKind(Connection owner, String table) throws SQLException {
        List<ForeignPolicy> foreign = foreignPolicies(owner, table);
        if (foreign.isEmpty()) {
            return PolicyKind.PERMISSIVE;
        }
        if (foreign.stream().anyMatch(ForeignPolicy::grantsObservedRole)) {
            return PolicyKind.RESTRICTIVE;
        }
        throw new IllegalStateException(("Table '%s' has RLS policies (%s) but none is permissive for role '%s'; "
                + "the observer would see nothing. Add a permissive policy for %s or exclude the table.")
                .formatted(table, foreign.stream().map(ForeignPolicy::name).toList(), ROLE, ROLE));
    }

    private record ForeignPolicy(String name, boolean permissive, List<String> roles) {

        boolean grantsObservedRole() {
            return permissive && (roles.contains("public") || roles.contains(ROLE));
        }
    }

    private static List<ForeignPolicy> foreignPolicies(Connection owner, String table) throws SQLException {
        String sql = "SELECT policyname, permissive, roles::text[] FROM pg_policies "
                + "WHERE schemaname = current_schema() AND tablename = ? AND policyname <> ? ORDER BY policyname";
        List<ForeignPolicy> policies = new ArrayList<>();
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, POLICY);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String[] roles = (String[]) rs.getArray(3).getArray();
                    policies.add(new ForeignPolicy(rs.getString(1), "PERMISSIVE".equals(rs.getString(2)),
                            List.of(roles)));
                }
            }
        }
        return policies;
    }
}
