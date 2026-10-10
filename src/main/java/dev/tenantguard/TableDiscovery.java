package dev.tenantguard;

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
 * Finds the tables to observe: every base table in the current schema that has the tenant column.
 * Tables the observer cannot be installed on are returned as skipped, with the reason, instead of
 * failing the whole run; explicit table lists still go through {@link ObserverInstaller}'s refusal.
 */
public final class TableDiscovery {

    /** @param tables observable, in name order; @param skipped table name to human-readable reason. */
    public record Result(List<String> tables, Map<String, String> skipped) {

        public Result {
            tables = List.copyOf(tables);
            skipped = Collections.unmodifiableMap(new TreeMap<>(skipped));
        }
    }

    /** The probe table carries the tenant column on purpose; Flyway's history is never tenant data. */
    private static final Set<String> INTERNAL = Set.of(ObserverInstaller.PROBE_TABLE, "flyway_schema_history");

    private TableDiscovery() {
    }

    public static Result discover(Connection owner, String tenantColumn) throws SQLException {
        String column = Identifiers.require(tenantColumn);
        List<String> tables = new ArrayList<>();
        Map<String, String> skipped = new TreeMap<>();
        for (String table : tablesWithColumn(owner, column)) {
            Optional<String> reason = unsupported(owner, table);
            if (reason.isPresent()) {
                skipped.put(table, reason.get());
            } else {
                tables.add(table);
            }
        }
        return new Result(tables, skipped);
    }

    private static List<String> tablesWithColumn(Connection owner, String column) throws SQLException {
        String sql = """
                SELECT c.table_name
                FROM information_schema.columns c
                JOIN information_schema.tables t
                  ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                WHERE c.table_schema = current_schema() AND c.column_name = ? AND t.table_type = 'BASE TABLE'
                ORDER BY c.table_name""";
        List<String> tables = new ArrayList<>();
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            statement.setString(1, column);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString(1);
                    if (!INTERNAL.contains(table)) {
                        tables.add(table);
                    }
                }
            }
        }
        return tables;
    }

    private static Optional<String> unsupported(Connection owner, String table) throws SQLException {
        try {
            Identifiers.require(table);
        } catch (IllegalArgumentException e) {
            return Optional.of("unsupported identifier: only unquoted lower-case table names are observed");
        }
        return ObserverInstaller.foreignPolicy(owner, table).map(policy -> ("existing RLS policy '%s': "
                + "a permissive observer policy would be OR-ed with it and switch the app's own isolation off")
                .formatted(policy));
    }
}
