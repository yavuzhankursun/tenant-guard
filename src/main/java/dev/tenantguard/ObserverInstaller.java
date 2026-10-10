package dev.tenantguard;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Installs the observer: a non-owner, non-bypass role and a policy that always returns true but
 * raises a NOTICE for every row the executor reads (USING) and every row a statement writes
 * (WITH CHECK). No FORCE ROW LEVEL SECURITY: the owner (migrations, seeder) must bypass the
 * observer, only the role the app switches into is watched. Must run as the table owner or a
 * superuser, after the schema exists.
 *
 * <p>On a table that already has its own permissive policy the observer is created
 * {@code AS RESTRICTIVE}: Postgres ORs permissive policies, so a permissive always-true observer
 * would switch the app's isolation off, while a restrictive one is AND-ed and leaves it intact.
 * Rows the app's policy rejects are then never observed, which is right: they did not leak. The
 * same holds for writes: the app's WITH CHECK still rejects a foreign row.
 *
 * <p>Writes: an INSERT reports its new rows, an UPDATE its old rows as reads and its new rows as
 * writes (and, when it needs SELECT rights, the new rows as reads too: Postgres checks that they
 * stay visible). A DELETE has no new row, so a foreign delete is only seen as a read.
 *
 * <p>Notices: {@code tg:<nonce>|r|<table>|<tenant>} for reads, {@code |w|} for writes. Every
 * install bakes a fresh random nonce into both functions and returns it ({@link Installation});
 * {@link RowRead#parse} accepts only nonces the guard trusts, so the app's own
 * {@code RAISE NOTICE 'tg|...'}, or a tenant-controlled string the app raises, is never an
 * observation. The nonce is in {@code pg_proc.prosrc}, readable by any role: it keeps strings out,
 * not code that runs arbitrary SQL against the catalog. Re-installing replaces the functions in
 * place, so pooled connections keep working and raise the new nonce from their next statement.
 *
 * <p>Location: {@code tg_observe}, {@code tg_observe_write} and {@code tg_probe} go into the
 * installing connection's current schema (the first existing schema on its search_path, where an
 * unqualified CREATE would go), and every policy calls the functions schema-qualified, so a table
 * in any schema can be observed. The observed role gets USAGE and table privileges on the
 * search_path's schemas and on every schema holding an observed table, not on a hard-coded
 * {@code public}: with {@code search_path = tenancy} a public-only grant hid the probe itself
 * ("relation tg_probe does not exist").
 *
 * <p>Tables created later: {@code GRANT ... ON ALL TABLES} covers existing tables only, so the
 * installing role's default privileges in those schemas grant the observed role every table and
 * sequence it creates afterwards. Without them, a fixture table a test creates after the install
 * fails with "permission denied" while the guard is active. Such a table has no policy and is not
 * observed until the next install names it. Default privileges live in {@code pg_default_acl}:
 * dropping {@code tg_app} later needs {@code DROP OWNED BY tg_app} first.
 *
 * <p>Every app-chosen name (table, schema, tenant column) is resolved against the catalog and
 * double-quoted, so mixed-case and reserved-word names work; see {@link Identifiers#quote}.
 *
 * <p>Tenant values: the policy reports {@code tenant_column::text}, Postgres' canonical text form
 * ({@code 2} for integers, lower-case dashed uuids). Actors are compared with it exactly; see
 * {@link TenantGuard#callAs}.
 */
public final class ObserverInstaller {

    public static final String ROLE = "tg_app";
    public static final String PROBE_TABLE = "tg_probe";
    static final String POLICY = "tg_observer";
    static final int LOCK_TIMEOUT_SECONDS = 30;
    private static final String LOCK_NOT_AVAILABLE = "55P03";
    static final String FUNCTION = "tg_observe";
    static final String WRITE_FUNCTION = "tg_observe_write";
    private static final int NONCE_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ObserverInstaller() {
    }

    /**
     * What one install wrote: hand it to {@link TenantGuard#trust} so the guard accepts its notices.
     *
     * @param location where the observer functions live (server, database, schema); a re-install at
     *                 the same location replaces the functions, and so the trusted nonce
     * @param nonce    32 lower-case hex digits from a CSPRNG, the signature of every notice
     */
    public record Installation(String location, String nonce) {

        public Installation {
            if (location == null) {
                throw new IllegalArgumentException("location is required");
            }
            if (nonce == null || !nonce.matches("[0-9a-f]{" + NONCE_BYTES * 2 + "}")) {
                throw new IllegalArgumentException("nonce must be " + NONCE_BYTES * 2 + " lower-case hex digits");
            }
        }

        /** Without the nonce: it ends up in logs, and in a log it is half a forgery kit. */
        @Override
        public String toString() {
            return "Installation[location=" + location + "]";
        }
    }

    /**
     * @param tables {@code table} or {@code schema.table}, case-sensitive as in the catalog; bare names
     *               resolve through the owner connection's search_path
     * @return the nonce the observer now signs with; the guard ignores the notices until it trusts it
     */
    public static Installation install(Connection owner, String tenantColumn, List<String> tables) throws SQLException {
        String column = Identifiers.quote(tenantColumn);
        String homeSchema = currentSchema(owner);
        String home = Identifiers.quote(homeSchema);
        Map<ObservedTable, Boolean> restrictive = new LinkedHashMap<>();
        for (String table : tables) {
            ObservedTable observed = ObservedTable.resolve(owner, table);
            restrictive.put(observed, policyKind(owner, observed) == PolicyKind.RESTRICTIVE);
        }
        String probe = home + "." + Identifiers.quote(PROBE_TABLE);
        String observe = home + "." + Identifiers.quote(FUNCTION);
        String observeWrite = home + "." + Identifiers.quote(WRITE_FUNCTION);
        String nonce = newNonce();

        List<String> ddl = new ArrayList<>(roleDdl());
        ddl.add(functionDdl(observe, nonce, RowRead.Kind.READ));
        ddl.add(functionDdl(observeWrite, nonce, RowRead.Kind.WRITE));
        // The probe's policy does not read its column: a probe left behind by an install with
        // another tenant column (company_id, then tenant_id) must keep working.
        ddl.add("CREATE TABLE IF NOT EXISTS %s (tenant text NOT NULL)".formatted(probe));
        ddl.add("INSERT INTO %1$s SELECT 'tg-probe' WHERE NOT EXISTS (SELECT 1 FROM %1$s)".formatted(probe));
        grantedSchemas(owner, restrictive.keySet()).forEach(schema -> ddl.addAll(grantDdl(Identifiers.quote(schema))));
        // Labels are allowlisted identifiers joined by '.', so they cannot break out of the literal.
        restrictive.forEach((table, asRestrictive) -> ddl.addAll(policyDdl(table.sql(), asRestrictive,
                "%s('%s', %s::text)".formatted(observe, table.label(), column),
                "%s('%s', %s::text)".formatted(observeWrite, table.label(), column))));
        // Nothing writes the probe; WITH CHECK (true) keeps it out of the write channel.
        ddl.addAll(policyDdl(probe, false, "%s('%s', 'tg-probe')".formatted(observe, PROBE_TABLE), "true"));

        Installation installation = new Installation(location(owner, homeSchema), nonce);
        execute(owner, ddl);
        return installation;
    }

    private static List<String> roleDdl() {
        return List.of(
                """
                DO $$ BEGIN
                  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%1$s') THEN
                    CREATE ROLE %1$s NOLOGIN NOSUPERUSER NOBYPASSRLS;
                  END IF;
                END $$""".formatted(ROLE),
                // Granting needs ADMIN on the role. An app role that is not a superuser was usually granted
                // tg_app once by whoever created it, and may not grant it again. PG16 split SET from membership.
                """
                DO $$ BEGIN
                  IF NOT pg_has_role(current_user, '%1$s',
                      CASE WHEN current_setting('server_version_num')::int >= 160000 THEN 'SET' ELSE 'MEMBER' END) THEN
                    GRANT %1$s TO CURRENT_USER;
                  END IF;
                END $$""".formatted(ROLE));
    }

    /** The nonce is hex and the kind code a letter, so neither can break out of the literal. */
    private static String functionDdl(String function, String nonce, RowRead.Kind kind) {
        return """
                CREATE OR REPLACE FUNCTION %s(tbl text, row_tenant text) RETURNS boolean
                LANGUAGE plpgsql VOLATILE AS $$
                BEGIN
                  RAISE NOTICE '%s%s|%s|%%|%%', tbl, row_tenant;
                  RETURN true;
                END $$""".formatted(function, RowRead.PREFIX, nonce, kind.code());
    }

    /** Existing objects now, and through the installing role's default privileges, the ones it creates later. */
    private static List<String> grantDdl(String schema) {
        return List.of(
                "GRANT USAGE ON SCHEMA %s TO %s".formatted(schema, ROLE),
                "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %s TO %s".formatted(schema, ROLE),
                "GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %s TO %s".formatted(schema, ROLE),
                "ALTER DEFAULT PRIVILEGES IN SCHEMA %s GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %s"
                        .formatted(schema, ROLE),
                "ALTER DEFAULT PRIVILEGES IN SCHEMA %s GRANT USAGE, SELECT ON SEQUENCES TO %s".formatted(schema, ROLE));
    }

    private static String newNonce() {
        byte[] bytes = new byte[NONCE_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Server and database as the client names them (the JDBC URL without its parameters, which may
     * carry credentials), plus the schema the functions live in. Two DataSources on one database
     * under different URLs get two locations; both nonces stay trusted, the stale one harmlessly.
     */
    private static String location(Connection owner, String homeSchema) throws SQLException {
        String url = owner.getMetaData().getURL();
        String server = url == null ? "" : url.split("\\?", 2)[0];
        try (Statement statement = owner.createStatement();
             ResultSet rs = statement.executeQuery("SELECT current_database()")) {
            rs.next();
            return server + "|" + rs.getString(1) + "|" + homeSchema;
        }
    }

    private static void execute(Connection owner, List<String> ddl) throws SQLException {
        try (Statement statement = owner.createStatement()) {
            // ENABLE ROW LEVEL SECURITY takes an exclusive lock: a session idle in a transaction on a
            // tenant table (a leaked test transaction, a pool that does not roll back on return) would
            // make the install hang forever. Fail instead, and say what to look for.
            statement.execute("SET lock_timeout = '" + LOCK_TIMEOUT_SECONDS + "s'");
            try {
                for (String sql : ddl) {
                    statement.execute(sql);
                }
            } catch (SQLException e) {
                if (LOCK_NOT_AVAILABLE.equals(e.getSQLState())) {
                    throw new SQLException(("TenantGuard could not lock a tenant table within %ds to install the "
                            + "observer: another session holds it (idle in transaction? a connection pool that "
                            + "does not roll back on return?). Holders: %s. %s")
                            .formatted(LOCK_TIMEOUT_SECONDS, lockHolders(owner), e.getMessage()), e.getSQLState(), e);
                }
                throw e;
            } finally {
                statement.execute("RESET lock_timeout");
            }
        }
    }

    /**
     * Other sessions holding relation locks, for the lock-timeout message. Best effort: on a
     * connection whose transaction the failed DDL aborted, the catalog query fails too.
     */
    private static String lockHolders(Connection owner) {
        String sql = """
                SELECT a.pid, a.state, coalesce(a.application_name, ''), l.mode, l.relation::regclass::text,
                       left(coalesce(a.query, ''), 160)
                FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid
                WHERE l.locktype = 'relation' AND l.granted AND a.pid <> pg_backend_pid()
                  AND l.relation::regclass::text NOT LIKE 'pg_%'
                ORDER BY a.pid LIMIT 10""";
        try (Statement statement = owner.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            StringBuilder holders = new StringBuilder();
            while (rs.next()) {
                holders.append(holders.isEmpty() ? "" : "; ")
                        .append("pid ").append(rs.getInt(1)).append(' ').append(rs.getString(2))
                        .append(" app='").append(rs.getString(3)).append("' ").append(rs.getString(4))
                        .append(" on ").append(rs.getString(5)).append(" last query: ").append(rs.getString(6));
            }
            return holders.isEmpty() ? "none visible" : holders.toString();
        } catch (SQLException e) {
            return "unknown (" + e.getMessage() + ")";
        }
    }

    /** Where an unqualified CREATE goes; null when no schema on the search_path exists. */
    private static String currentSchema(Connection owner) throws SQLException {
        try (Statement statement = owner.createStatement();
             ResultSet rs = statement.executeQuery("SELECT current_schema()")) {
            String schema = rs.next() ? rs.getString(1) : null;
            if (schema == null) {
                throw new SQLException("no schema on the search_path exists, so the observer has nowhere to live",
                        "3F000");
            }
            return schema;
        }
    }

    /**
     * The observed role must reach everything the app reaches: every schema on the search_path (the
     * app's bare names, the probe, {@code tg_observe}) and every schema holding an observed table
     * (the app may qualify names off the path). Nothing else: a grant is a change to the database.
     * System schemas are skipped; they are readable already and not ours to grant on.
     */
    private static Set<String> grantedSchemas(Connection owner, Collection<ObservedTable> observed) throws SQLException {
        Set<String> schemas = new LinkedHashSet<>();
        String sql = "SELECT s FROM unnest(current_schemas(false)) s "
                + "WHERE s <> 'information_schema' AND left(s, 3) <> 'pg_'";
        try (Statement statement = owner.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                schemas.add(rs.getString(1));
            }
        }
        observed.forEach(table -> schemas.add(table.schema()));
        return schemas;
    }

    private static List<String> policyDdl(String table, boolean restrictive, String observe, String check) {
        return List.of(
                "ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(table),
                "DROP POLICY IF EXISTS %s ON %s".formatted(POLICY, table),
                "CREATE POLICY %s ON %s AS %s FOR ALL TO %s USING (%s) WITH CHECK (%s)"
                        .formatted(POLICY, table, restrictive ? "RESTRICTIVE" : "PERMISSIVE", ROLE, observe, check));
    }

    /** How the observer must be created on {@code table} given the policies already on it. */
    public enum PolicyKind { PERMISSIVE, RESTRICTIVE }

    /**
     * No foreign policy: permissive. A foreign permissive policy that applies to the observed role:
     * restrictive, so it is AND-ed. Foreign policies that never grant the role anything: refused,
     * because a restrictive-only table denies every row and the observer would see nothing.
     *
     * @param table {@code table} or {@code schema.table}, resolved like {@link #install} does
     */
    public static PolicyKind policyKind(Connection owner, String table) throws SQLException {
        return policyKind(owner, ObservedTable.resolve(owner, table));
    }

    static PolicyKind policyKind(Connection owner, ObservedTable table) throws SQLException {
        List<ForeignPolicy> foreign = foreignPolicies(owner, table);
        if (foreign.isEmpty()) {
            return PolicyKind.PERMISSIVE;
        }
        if (foreign.stream().anyMatch(ForeignPolicy::grantsObservedRole)) {
            return PolicyKind.RESTRICTIVE;
        }
        throw new IllegalStateException(("Table '%s' has RLS policies (%s) but none is permissive for role '%s'; "
                + "the observer would see nothing. Add a permissive policy for %s or exclude the table.")
                .formatted(table.label(), foreign.stream().map(ForeignPolicy::name).toList(), ROLE, ROLE));
    }

    private record ForeignPolicy(String name, boolean permissive, List<String> roles) {

        boolean grantsObservedRole() {
            return permissive && (roles.contains("public") || roles.contains(ROLE));
        }
    }

    private static List<ForeignPolicy> foreignPolicies(Connection owner, ObservedTable table) throws SQLException {
        String sql = "SELECT policyname, permissive, roles::text[] FROM pg_policies "
                + "WHERE schemaname = ? AND tablename = ? AND policyname <> ? ORDER BY policyname";
        List<ForeignPolicy> policies = new ArrayList<>();
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            statement.setString(1, table.schema());
            statement.setString(2, table.name());
            statement.setString(3, POLICY);
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
