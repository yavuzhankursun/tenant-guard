package dev.tenantguard;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Rows touched by one statement execution, grouped by table. {@code actor} is null in passive mode.
 *
 * @param tenantsByTable        tenants of the rows the statement read (the policy's USING)
 * @param writtenTenantsByTable tenants of the rows it inserted or updated (the policy's WITH CHECK);
 *                              kept apart because "B read A's row" and "B wrote a row as A" are
 *                              different findings, and an UPDATE of a foreign row is both
 */
public record Observation(String sql, String actor, Map<String, Set<String>> tenantsByTable,
                          Map<String, Set<String>> writtenTenantsByTable) {

    public Observation {
        tenantsByTable = Map.copyOf(tenantsByTable);
        writtenTenantsByTable = Map.copyOf(writtenTenantsByTable);
    }

    /** Reads only; kept so code written before writes were observed still compiles. */
    public Observation(String sql, String actor, Map<String, Set<String>> tenantsByTable) {
        this(sql, actor, tenantsByTable, Map.of());
    }

    static Observation of(String sql, String actor, List<RowRead> rows) {
        return new Observation(sql, actor, group(rows, RowRead.Kind.READ), group(rows, RowRead.Kind.WRITE));
    }

    private static Map<String, Set<String>> group(List<RowRead> rows, RowRead.Kind kind) {
        Map<String, Set<String>> grouped = rows.stream().filter(row -> row.kind() == kind).collect(Collectors.groupingBy(
                RowRead::table, TreeMap::new,
                Collectors.mapping(RowRead::tenant, Collectors.toCollection(TreeSet::new))));
        return grouped.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, e -> Set.copyOf(e.getValue())));
    }

    /** Tenants of the rows read; writes are not part of the passive multi-tenant rule. */
    Set<String> allTenants() {
        return tenantsByTable.values().stream()
                .flatMap(Set::stream)
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
