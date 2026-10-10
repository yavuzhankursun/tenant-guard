package dev.tenantguard;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Rows read by one statement execution, grouped by table. {@code actor} is null in passive mode. */
public record Observation(String sql, String actor, Map<String, Set<String>> tenantsByTable) {

    static Observation of(String sql, String actor, List<RowRead> rows) {
        Map<String, Set<String>> grouped = rows.stream().collect(Collectors.groupingBy(
                RowRead::table, TreeMap::new,
                Collectors.mapping(RowRead::tenant, Collectors.toCollection(TreeSet::new))));
        Map<String, Set<String>> frozen = grouped.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, e -> Set.copyOf(e.getValue())));
        return new Observation(sql, actor, frozen);
    }

    Set<String> allTenants() {
        return tenantsByTable.values().stream()
                .flatMap(Set::stream)
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
