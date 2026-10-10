package dev.tenantguard;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Finds the tables to observe: every base or partitioned table that has the tenant column, in the
 * schemas of the connection's search_path or in the schemas named explicitly. The search_path,
 * not just {@code current_schema()}: an app running with {@code search_path = tenancy, public}
 * reads tables from both, and a table we do not discover is a table we do not observe.
 *
 * <p>Tables are named by their {@link ObservedTable#label() label}: the bare name when it resolves
 * to that table from this connection, {@code schema.table} otherwise. Tables the observer cannot be
 * installed on (names outside the {@link Identifiers#quote identifier allowlist}, RLS policies that
 * never grant the observed role anything) are returned as skipped, with the reason, instead of
 * failing the whole run; explicit table lists still go through {@link ObserverInstaller}'s refusal.
 */
public final class TableDiscovery {

    /** @param tables observable, in label order; @param skipped table label to human-readable reason. */
    public record Result(List<String> tables, Map<String, String> skipped) {

        public Result {
            tables = List.copyOf(tables);
            skipped = Collections.unmodifiableMap(new TreeMap<>(skipped));
        }
    }

    /** Our own probe and Flyway's history are never tenant data, in whatever schema they live. */
    private static final Set<String> INTERNAL = Set.of(ObserverInstaller.PROBE_TABLE, "flyway_schema_history");

    private static final String TABLES_WITH_COLUMN = """
            SELECT n.nspname, c.relname, pg_table_is_visible(c.oid)
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            JOIN pg_attribute a ON a.attrelid = c.oid
            WHERE c.relkind IN ('r', 'p') AND a.attname = ? AND a.attnum > 0 AND NOT a.attisdropped
              AND n.nspname::text = ANY (?)""";

    private static final String UNSUPPORTED = "unsupported identifier: only names made of letters, digits, _ and $ "
            + "are observed (no quotes, dots, spaces or control characters)";

    private TableDiscovery() {
    }

    /** Searches every schema on the connection's search_path. */
    public static Result discover(Connection owner, String tenantColumn) throws SQLException {
        return discover(owner, tenantColumn, List.of());
    }

    /**
     * @param schemas schemas to search, as stored in the catalog (case-sensitive); empty means the
     *                connection's search_path
     */
    public static Result discover(Connection owner, String tenantColumn, List<String> schemas) throws SQLException {
        Identifiers.quote(tenantColumn);
        schemas.forEach(Identifiers::quote);
        List<String> searched = schemas.isEmpty() ? searchPath(owner) : schemas;
        Map<String, ObservedTable> candidates = new TreeMap<>();
        Map<String, String> skipped = new TreeMap<>();
        for (ObservedTable table : tablesWithColumn(owner, tenantColumn, searched)) {
            if (!Identifiers.isQuotable(table.schema()) || !Identifiers.isQuotable(table.name())) {
                skipped.put(table.label(), UNSUPPORTED);
            } else {
                candidates.put(table.label(), table);
            }
        }
        List<String> tables = new ArrayList<>();
        for (Map.Entry<String, ObservedTable> candidate : candidates.entrySet()) {
            Optional<String> reason = refused(owner, candidate.getValue());
            if (reason.isPresent()) {
                skipped.put(candidate.getKey(), reason.get());
            } else {
                tables.add(candidate.getKey());
            }
        }
        return new Result(tables, skipped);
    }

    private static List<String> searchPath(Connection owner) throws SQLException {
        List<String> schemas = new ArrayList<>();
        try (PreparedStatement statement = owner.prepareStatement("SELECT unnest(current_schemas(false))::text");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                schemas.add(rs.getString(1));
            }
        }
        return schemas;
    }

    private static List<ObservedTable> tablesWithColumn(Connection owner, String column, List<String> schemas)
            throws SQLException {
        List<ObservedTable> tables = new ArrayList<>();
        try (PreparedStatement statement = owner.prepareStatement(TABLES_WITH_COLUMN)) {
            Array names = owner.createArrayOf("text", schemas.toArray());
            statement.setString(1, column);
            statement.setArray(2, names);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    if (!INTERNAL.contains(rs.getString(2))) {
                        tables.add(ObservedTable.of(rs.getString(1), rs.getString(2), rs.getBoolean(3)));
                    }
                }
            }
        }
        return tables;
    }

    private static Optional<String> refused(Connection owner, ObservedTable table) throws SQLException {
        try {
            ObserverInstaller.policyKind(owner, table);
            return Optional.empty();
        } catch (IllegalStateException e) {
            return Optional.of(e.getMessage());
        }
    }
}
