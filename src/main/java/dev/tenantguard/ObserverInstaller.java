package dev.tenantguard;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Installs the observer: a non-owner, non-bypass role and a permissive policy that always returns
 * true but raises a NOTICE for every row the executor reads. No FORCE ROW LEVEL SECURITY: the owner
 * (migrations, seeder) must bypass the observer, only the role the app switches into is watched.
 * Must run as the table owner or a superuser, after the schema exists.
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
        refuseForeignPolicies(owner, observed);

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
        Stream.concat(observed.stream(), Stream.of(PROBE_TABLE))
                .forEach(table -> ddl.addAll(policyDdl(table, column)));

        try (Statement statement = owner.createStatement()) {
            for (String sql : ddl) {
                statement.execute(sql);
            }
        }
    }

    private static List<String> policyDdl(String table, String column) {
        return List.of(
                "ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(table),
                "DROP POLICY IF EXISTS %s ON %s".formatted(POLICY, table),
                // WITH CHECK (true): the write side is out of MVP scope.
                "CREATE POLICY %s ON %s FOR ALL TO %s USING (tg_observe('%s', %s::text)) WITH CHECK (true)"
                        .formatted(POLICY, table, ROLE, table, column));
    }

    /**
     * Postgres ORs permissive policies, so adding an always-true one next to an existing policy would
     * silently switch the app's own isolation off. RESTRICTIVE support is post-MVP; refuse for now.
     */
    private static void refuseForeignPolicies(Connection owner, List<String> tables) throws SQLException {
        for (String table : tables) {
            Optional<String> policy = foreignPolicy(owner, table);
            if (policy.isPresent()) {
                throw new IllegalStateException(("Table '%s' already has RLS policy '%s'. TenantGuard "
                        + "does not support tables with existing policies yet.").formatted(table, policy.get()));
            }
        }
    }

    /** The first RLS policy on {@code table} that is not the observer, if any. */
    static Optional<String> foreignPolicy(Connection owner, String table) throws SQLException {
        String sql = "SELECT policyname FROM pg_policies "
                + "WHERE schemaname = current_schema() AND tablename = ? AND policyname <> ? ORDER BY policyname";
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, POLICY);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }
}
