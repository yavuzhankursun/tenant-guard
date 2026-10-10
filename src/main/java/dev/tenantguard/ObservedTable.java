package dev.tenantguard;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * A table resolved against the catalog, so DDL never depends on the installing connection's
 * search_path and two tables of the same name in different schemas stay apart.
 *
 * <p>{@code label} is what the observer reports in {@link RowRead#table()}: the bare name when it
 * resolves to this table from the installing connection's search_path, {@code schema.name}
 * otherwise (a schema off the path, or a name shadowed by an earlier schema). That keeps the common
 * case ({@code invoice} in {@code public}) unchanged and makes every label unambiguous.
 *
 * @param schema catalog name of the schema, exactly as stored
 * @param name   catalog name of the table, exactly as stored
 */
record ObservedTable(String schema, String name, String label) {

    private static final String RESOLVE = """
            SELECT n.nspname, c.relname, pg_table_is_visible(c.oid)
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.oid = to_regclass(?)""";
    private static final String UNDEFINED_TABLE = "42P01";

    /** Both parts double-quoted: the form DDL uses. */
    String sql() {
        return Identifiers.quote(schema) + "." + Identifiers.quote(name);
    }

    static ObservedTable of(String schema, String name, boolean visible) {
        return new ObservedTable(schema, name, visible ? name : schema + "." + name);
    }

    /**
     * {@code table} or {@code schema.table}, case-sensitive, as stored in the catalog. A bare name
     * resolves through the connection's search_path, like the app's own SQL would.
     */
    static ObservedTable resolve(Connection connection, String table) throws SQLException {
        String quoted = Identifiers.quoteQualified(table);
        try (PreparedStatement statement = connection.prepareStatement(RESOLVE)) {
            statement.setString(1, quoted);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("relation %s does not exist (search_path: %s)"
                            .formatted(quoted, searchPath(connection)), UNDEFINED_TABLE);
                }
                return of(rs.getString(1), rs.getString(2), rs.getBoolean(3));
            }
        }
    }

    private static String searchPath(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SHOW search_path");
             ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getString(1) : "?";
        }
    }
}
