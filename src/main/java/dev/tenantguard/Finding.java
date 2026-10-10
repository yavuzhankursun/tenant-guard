package dev.tenantguard;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * A suspicious read. Every rule here is SUSPECT-level: the observer sees rows the executor read,
 * not rows that left the database, so a correctly filtered join can still trigger it.
 */
public record Finding(Rule rule, String sql, String table, Set<String> tenants) {

    public enum Rule {
        /** Active mode: the acting tenant read a row owned by another tenant. Catches findById IDOR. */
        ACTOR_MISMATCH,
        /** Passive mode: one statement read rows of more than one tenant. Misses single-row IDOR. */
        MULTI_TENANT_STATEMENT
    }

    static List<Finding> evaluate(Observation observation) {
        List<Finding> findings = new ArrayList<>();
        String actor = observation.actor();
        if (actor != null) {
            observation.tenantsByTable().forEach((table, tenants) -> {
                Set<String> foreign = new TreeSet<>(tenants);
                foreign.remove(actor);
                if (!foreign.isEmpty()) {
                    findings.add(new Finding(Rule.ACTOR_MISMATCH, observation.sql(), table, Set.copyOf(foreign)));
                }
            });
        }
        Set<String> all = observation.allTenants();
        if (all.size() > 1) {
            String tables = String.join(",", new TreeSet<>(observation.tenantsByTable().keySet()));
            findings.add(new Finding(Rule.MULTI_TENANT_STATEMENT, observation.sql(), tables, Set.copyOf(all)));
        }
        return List.copyOf(findings);
    }
}
