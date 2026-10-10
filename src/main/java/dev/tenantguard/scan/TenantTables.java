package dev.tenantguard.scan;

import dev.tenantguard.ObserverInstaller;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The tables a write can hurt: every table on the search_path with the tenant column (partitions
 * are read through their parent, the observer's probe table is ours). Owns the two statements the
 * write scan runs as the owner: a snapshot of all rows, and deleting the scan's two tenants' rows
 * children first. Deleting by tenant rather than truncating leaves every other tenant's data, and
 * every table without the tenant column, exactly as it was.
 */
final class TenantTables {

    /** Catalog names are quoted, never escaped: anything that could leave the quotes is refused. */
    private static final Pattern QUOTABLE = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_$]{0,62}");

    private static final String TABLES = """
            SELECT c.oid::bigint, n.nspname, c.relname, pg_table_is_visible(c.oid)
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            JOIN pg_attribute a ON a.attrelid = c.oid
            WHERE c.relkind IN ('r', 'p') AND NOT c.relispartition AND c.relname <> ?
              AND a.attname = ? AND a.attnum > 0 AND NOT a.attisdropped
              AND n.nspname = ANY (current_schemas(false))
            ORDER BY n.nspname, c.relname""";

    private static final String PRIMARY_KEY = """
            SELECT a.attname FROM pg_index i
            JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)
            WHERE i.indrelid = ?::bigint::oid AND i.indisprimary
            ORDER BY a.attnum""";

    private static final String FOREIGN_KEYS = """
            SELECT conrelid::bigint, confrelid::bigint FROM pg_constraint
            WHERE contype = 'f' AND conrelid <> confrelid""";

    /** {@code label} is how notes name the table: bare when visible on the search_path. */
    record Table(long oid, String label, String quoted, String snapshotSql) {
    }

    private final List<Table> tables;
    private final String tenantColumn;

    private TenantTables(List<Table> tables, String tenantColumn) {
        this.tables = List.copyOf(tables);
        this.tenantColumn = tenantColumn;
    }

    static TenantTables discover(Connection owner, String tenantColumn) throws SQLException {
        String column = quote(tenantColumn);
        List<Table> found = new ArrayList<>();
        try (PreparedStatement statement = owner.prepareStatement(TABLES)) {
            statement.setString(1, ObserverInstaller.PROBE_TABLE);
            statement.setString(2, tenantColumn);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    long oid = rs.getLong(1);
                    String quoted = quote(rs.getString(2)) + "." + quote(rs.getString(3));
                    String label = rs.getBoolean(4) ? rs.getString(3) : rs.getString(2) + "." + rs.getString(3);
                    found.add(new Table(oid, label, quoted, snapshotSql(owner, oid, quoted, column)));
                }
            }
        }
        return new TenantTables(childrenFirst(owner, found), column);
    }

    List<String> labels() {
        return tables.stream().map(Table::label).toList();
    }

    /** Labels in alphabetical order so notes read the same on every run. */
    RowSnapshot snapshot(Connection owner) throws SQLException {
        Map<String, List<RowSnapshot.Row>> rows = new TreeMap<>();
        for (Table table : tables) {
            List<RowSnapshot.Row> tableRows = new ArrayList<>();
            try (PreparedStatement statement = owner.prepareStatement(table.snapshotSql());
                 ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    tableRows.add(new RowSnapshot.Row(rs.getString(1), rs.getString(2), rs.getString(3)));
                }
            }
            rows.put(table.label(), tableRows);
        }
        return new RowSnapshot(rows);
    }

    /** Children first, so foreign keys between tenant tables never block the delete. */
    void deleteTenants(Connection owner, List<String> tenants) throws SQLException {
        for (Table table : tables) {
            String sql = "DELETE FROM %s WHERE %s::text = ANY (?)".formatted(table.quoted(), tenantColumn);
            try (PreparedStatement statement = owner.prepareStatement(sql)) {
                statement.setArray(1, owner.createArrayOf("text", tenants.toArray()));
                statement.executeUpdate();
            }
        }
    }

    /** md5 of the row's text form: any column change shows, and the server does the hashing. */
    private static String snapshotSql(Connection owner, long oid, String quoted, String column) throws SQLException {
        List<String> keyColumns = new ArrayList<>();
        try (PreparedStatement statement = owner.prepareStatement(PRIMARY_KEY)) {
            statement.setLong(1, oid);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    keyColumns.add("t." + quote(rs.getString(1)));
                }
            }
        }
        String key = keyColumns.isEmpty() ? "NULL::text" : "row(%s)::text".formatted(String.join(", ", keyColumns));
        return "SELECT %s, t.%s::text, md5(t::text) FROM %s t".formatted(key, column, quoted);
    }

    /** Topological order over foreign keys among the tenant tables; a cycle keeps its catalog order. */
    private static List<Table> childrenFirst(Connection owner, List<Table> tables) throws SQLException {
        Map<Long, Table> byOid = new LinkedHashMap<>();
        tables.forEach(t -> byOid.put(t.oid(), t));
        Map<Long, Set<Long>> parentsOf = new HashMap<>();
        try (PreparedStatement statement = owner.prepareStatement(FOREIGN_KEYS);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                if (byOid.containsKey(rs.getLong(1)) && byOid.containsKey(rs.getLong(2))) {
                    parentsOf.computeIfAbsent(rs.getLong(1), k -> new HashSet<>()).add(rs.getLong(2));
                }
            }
        }
        List<Table> ordered = new ArrayList<>();
        Set<Long> placed = new HashSet<>();
        while (ordered.size() < tables.size()) {
            int before = ordered.size();
            for (Table table : tables) {
                boolean noChildLeft = tables.stream().noneMatch(c -> !placed.contains(c.oid())
                        && parentsOf.getOrDefault(c.oid(), Set.of()).contains(table.oid()));
                if (!placed.contains(table.oid()) && noChildLeft) {
                    ordered.add(table);
                    placed.add(table.oid());
                }
            }
            if (ordered.size() == before) {
                tables.stream().filter(t -> !placed.contains(t.oid())).forEach(t -> {
                    ordered.add(t);
                    placed.add(t.oid());
                });
            }
        }
        return ordered;
    }

    private static String quote(String identifier) {
        if (identifier == null || !QUOTABLE.matcher(identifier).matches()) {
            throw new IllegalStateException(("Cannot snapshot tenant table or column '%s': only letters, digits, "
                    + "_ and $ are supported in names, and a table left out of the snapshot would hide its writes")
                    .formatted(identifier));
        }
        return "\"" + identifier + "\"";
    }
}
