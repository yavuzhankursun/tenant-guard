package dev.tenantguard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A suspicious row access. Every rule here is SUSPECT-level: the observer sees rows the executor
 * touched, not rows that left the database, so a correctly filtered join can still trigger it.
 */
public record Finding(Rule rule, String sql, String table, Set<String> tenants) {

    public enum Rule {
        /** Active mode: the acting tenant read a row owned by another tenant. Catches findById IDOR. */
        ACTOR_MISMATCH,
        /** Passive mode: one statement read rows of more than one tenant. Misses single-row IDOR. */
        MULTI_TENANT_STATEMENT,
        /**
         * Active mode: the acting tenant inserted or updated a row whose tenant is not its own: a
         * {@code tenant_id} taken from the request body, or an UPDATE that edits or moves a foreign
         * row (which also reads it, so ACTOR_MISMATCH comes with it). A DELETE writes no new row and
         * is only seen as a read.
         */
        WRITE_MISMATCH
    }

    static List<Finding> evaluate(Observation observation) {
        List<Finding> findings = new ArrayList<>();
        String actor = observation.actor();
        if (actor != null) {
            findings.addAll(foreign(Rule.ACTOR_MISMATCH, observation.sql(), actor, observation.tenantsByTable()));
            findings.addAll(foreign(Rule.WRITE_MISMATCH, observation.sql(), actor, observation.writtenTenantsByTable()));
        }
        Set<String> all = observation.allTenants();
        if (all.size() > 1) {
            String tables = String.join(",", new TreeSet<>(observation.tenantsByTable().keySet()));
            findings.add(new Finding(Rule.MULTI_TENANT_STATEMENT, observation.sql(), tables, Set.copyOf(all)));
        }
        return List.copyOf(findings);
    }

    /** One finding per table that holds rows of a tenant other than {@code actor}. */
    private static List<Finding> foreign(Rule rule, String sql, String actor, Map<String, Set<String>> tenantsByTable) {
        List<Finding> findings = new ArrayList<>();
        new TreeMap<>(tenantsByTable).forEach((table, tenants) -> {
            Set<String> foreign = new TreeSet<>(tenants);
            foreign.remove(actor);
            if (!foreign.isEmpty()) {
                findings.add(new Finding(rule, sql, table, Set.copyOf(foreign)));
            }
        });
        return findings;
    }
}
