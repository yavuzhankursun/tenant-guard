package dev.tenantguard.scan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Every row of every tenant table, reduced to (primary key, tenant, hash of the whole row). Why data
 * and not the HTTP status: a write endpoint that updates the victim's row and answers 404, or
 * answers 204 after a correctly filtered no-op, is indistinguishable by status. Comparing the
 * victim's rows before and after the attacker's call is the ground truth. Rows are sorted on
 * construction so two snapshots of unchanged tables are equal whatever order the server returned.
 *
 * @param tables table label to rows, in the order notes should list them
 */
record RowSnapshot(Map<String, List<Row>> tables) {

    /** {@code key} is the primary key as text, or null for a table without one (compared by hash). */
    record Row(String key, String tenant, String hash) {
    }

    private static final Comparator<Row> ORDER = Comparator.comparing(Row::key, Comparator.nullsFirst(String::compareTo))
            .thenComparing(Row::tenant, Comparator.nullsFirst(String::compareTo))
            .thenComparing(Row::hash);

    RowSnapshot {
        Map<String, List<Row>> sorted = new LinkedHashMap<>();
        tables.forEach((table, rows) -> sorted.put(table, rows.stream().sorted(ORDER).toList()));
        tables = Collections.unmodifiableMap(sorted);
    }

    /**
     * What happened to {@code victim}'s rows, one entry per table and kind of change, e.g.
     * {@code invoice: 1 row updated}. Rows of other tenants are ignored: the attacker changing its
     * own data is the endpoint working.
     */
    List<String> victimChanges(RowSnapshot after, String victim) {
        List<String> changes = new ArrayList<>();
        tables.forEach((table, before) -> {
            List<Row> now = after.tables().getOrDefault(table, List.of());
            Map<String, Integer> counts = Stream.concat(before.stream(), now.stream()).anyMatch(r -> r.key() == null)
                    ? byHash(before, now, victim) : byKey(before, now, victim);
            counts.forEach((kind, n) -> changes.add("%s: %d %s %s".formatted(table, n, n == 1 ? "row" : "rows", kind)));
        });
        return List.copyOf(changes);
    }

    private static Map<String, Integer> byKey(List<Row> before, List<Row> after, String victim) {
        Map<String, Row> now = after.stream().filter(r -> r.key() != null)
                .collect(Collectors.toMap(Row::key, Function.identity(), (a, b) -> a));
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Row> was = new HashMap<>();
        for (Row row : before) {
            was.put(row.key(), row);
            if (!victim.equals(row.tenant())) {
                continue;
            }
            Row current = now.get(row.key());
            String kind = current == null ? "deleted"
                    : !victim.equals(current.tenant()) ? "moved to tenant " + current.tenant()
                    : !current.hash().equals(row.hash()) ? "updated" : null;
            if (kind != null) {
                counts.merge(kind, 1, Integer::sum);
            }
        }
        long created = after.stream().filter(r -> victim.equals(r.tenant()))
                .filter(r -> !was.containsKey(r.key()) || !victim.equals(was.get(r.key()).tenant()))
                .count();
        return withCreated(sortedKinds(counts), created, victim);
    }

    /** Without a key an update cannot be told from a delete plus an insert; it is reported as both. */
    private static Map<String, Integer> byHash(List<Row> before, List<Row> after, String victim) {
        Map<String, Integer> balance = new HashMap<>();
        before.stream().filter(r -> victim.equals(r.tenant())).forEach(r -> balance.merge(r.hash(), -1, Integer::sum));
        after.stream().filter(r -> victim.equals(r.tenant())).forEach(r -> balance.merge(r.hash(), 1, Integer::sum));
        int deleted = balance.values().stream().filter(n -> n < 0).mapToInt(n -> -n).sum();
        int created = balance.values().stream().filter(n -> n > 0).mapToInt(Integer::intValue).sum();
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (deleted > 0) {
            counts.put("deleted", deleted);
        }
        return withCreated(counts, created, victim);
    }

    /** updated, deleted, moved (by tenant): the order a reader scans for "what did the attacker do". */
    private static Map<String, Integer> sortedKinds(Map<String, Integer> counts) {
        Map<String, Integer> sorted = new LinkedHashMap<>();
        for (String kind : List.of("updated", "deleted")) {
            if (counts.containsKey(kind)) {
                sorted.put(kind, counts.get(kind));
            }
        }
        new TreeMap<>(counts).forEach(sorted::putIfAbsent);
        return sorted;
    }

    private static Map<String, Integer> withCreated(Map<String, Integer> counts, long created, String victim) {
        if (created > 0) {
            counts.put("created with tenant " + victim, Math.toIntExact(created));
        }
        return counts;
    }
}
