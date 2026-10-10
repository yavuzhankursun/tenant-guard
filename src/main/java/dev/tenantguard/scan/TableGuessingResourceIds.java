package dev.tenantguard.scan;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import javax.sql.DataSource;

/**
 * Guesses the table behind {@code /invoices/{id}} from the preceding segment ({@code invoice},
 * {@code invoices}, snake_case of either) or from the variable name ({@code invoiceId}), then takes
 * the victim's smallest id. Names come from URLs, so every candidate must pass the identifier
 * allowlist before it gets anywhere near SQL; the tenant value is always a bind parameter.
 */
public final class TableGuessingResourceIds implements ResourceIds {

    /*
     * Resolved like the lookup query's own bare table name: through the search_path, first match wins.
     * current_schema() alone missed every table in the second path entry (search_path = tenancy, public).
     */
    private static final String TABLE_EXISTS = "SELECT 1 WHERE to_regclass(?) IS NOT NULL";
    private static final String COLUMNS = "SELECT attname FROM pg_attribute "
            + "WHERE attrelid = to_regclass(?) AND attnum > 0 AND NOT attisdropped";

    private final DataSource dataSource;
    private final String tenantColumn;
    private final String idColumn;

    public TableGuessingResourceIds(DataSource dataSource, String tenantColumn) {
        this(dataSource, tenantColumn, "id");
    }

    public TableGuessingResourceIds(DataSource dataSource, String tenantColumn, String idColumn) {
        this.dataSource = dataSource;
        this.tenantColumn = SqlIdentifiers.require(tenantColumn);
        this.idColumn = SqlIdentifiers.require(idColumn);
    }

    @Override
    public Resolution resolve(String pathTemplate, String variable, String tenant) {
        List<String> candidates = candidateTables(pathTemplate, variable);
        if (candidates.isEmpty()) {
            return Resolution.unresolved("cannot guess a table for {%s} in %s".formatted(variable, pathTemplate));
        }
        try (Connection connection = dataSource.getConnection()) {
            return firstMatch(connection, candidates, tenant);
        } catch (SQLException e) {
            return Resolution.unresolved("id lookup for {%s} failed: %s".formatted(variable, e.getMessage()));
        }
    }

    private Resolution firstMatch(Connection connection, List<String> candidates, String tenant) throws SQLException {
        List<String> problems = new ArrayList<>();
        for (String table : candidates) {
            if (!tableExists(connection, table)) {
                problems.add("no table " + table);
                continue;
            }
            Set<String> columns = columns(connection, table);
            if (!columns.contains(tenantColumn) || !columns.contains(idColumn)) {
                problems.add("table %s has no column %s".formatted(table,
                        columns.contains(tenantColumn) ? idColumn : tenantColumn));
                continue;
            }
            Optional<Object> id = smallestId(connection, table, tenant);
            return id.map(Resolution::of).orElseGet(() ->
                    Resolution.unresolved("table %s has no rows of tenant %s".formatted(table, tenant)));
        }
        return Resolution.unresolved(String.join("; ", problems));
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TABLE_EXISTS)) {
            statement.setString(1, SqlIdentifiers.quote(table));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static Set<String> columns(Connection connection, String table) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(COLUMNS)) {
            statement.setString(1, SqlIdentifiers.quote(table));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString(1));
                }
            }
        }
        return columns;
    }

    /** ORDER BY ... LIMIT 1 rather than min(): Postgres has no min(uuid). ::text so non-text tenant keys work. */
    private Optional<Object> smallestId(Connection connection, String table, String tenant) throws SQLException {
        String id = SqlIdentifiers.quote(idColumn);
        String sql = "SELECT %1$s FROM %2$s WHERE %3$s::text = ? ORDER BY %1$s LIMIT 1"
                .formatted(id, SqlIdentifiers.quote(table), SqlIdentifiers.quote(tenantColumn));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenant);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getObject(1)) : Optional.empty();
            }
        }
    }

    /** Candidate table names in preference order, already filtered to safe identifiers. */
    static List<String> candidateTables(String pathTemplate, String variable) {
        Set<String> names = new LinkedHashSet<>();
        precedingSegment(pathTemplate, variable).ifPresent(segment -> {
            String snake = snakeCase(segment);
            names.addAll(singulars(snake));
            names.add(snake);
            names.addAll(singulars(segment));
            names.add(segment);
        });
        String hint = snakeCase(variable);
        if (hint.endsWith("_id") && hint.length() > "_id".length()) {
            names.add(hint.substring(0, hint.length() - "_id".length()));
        }
        return names.stream().filter(SqlIdentifiers::isSafe).toList();
    }

    private static Optional<String> precedingSegment(String pathTemplate, String variable) {
        String[] segments = pathTemplate.split("/");
        for (int i = 1; i < segments.length; i++) {
            Matcher matcher = EndpointDiscovery.VARIABLE.matcher(segments[i]);
            if (matcher.matches() && matcher.group(1).equals(variable)) {
                String previous = segments[i - 1];
                boolean usable = !previous.isEmpty() && !EndpointDiscovery.VARIABLE.matcher(previous).find();
                return usable ? Optional.of(previous) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static List<String> singulars(String plural) {
        if (plural.endsWith("ies")) {
            return List.of(plural.substring(0, plural.length() - 3) + "y");
        }
        if (plural.matches(".*(s|x|z|ch|sh)es")) {
            return List.of(plural.substring(0, plural.length() - 2), plural.substring(0, plural.length() - 1));
        }
        if (plural.endsWith("s") && !plural.endsWith("ss")) {
            return List.of(plural.substring(0, plural.length() - 1));
        }
        return List.of();
    }

    private static String snakeCase(String name) {
        return name.replace('-', '_').replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
