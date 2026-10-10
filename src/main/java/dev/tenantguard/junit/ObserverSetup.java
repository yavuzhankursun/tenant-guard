package dev.tenantguard.junit;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What {@link TenantGuardExtension} installed for the current test. Inject it into a test method to
 * assert on coverage: a table in {@code skippedTables} is not observed, so leaks there go unseen.
 *
 * @param installedTables tables carrying the observer policy
 * @param skippedTables   table name to the reason it is not observed (auto-discovery only)
 * @param selfChecked     whether {@link dev.tenantguard.SelfCheck} ran and passed
 */
public record ObserverSetup(String tenantColumn, List<String> installedTables, Map<String, String> skippedTables,
                            boolean selfChecked) {

    public ObserverSetup {
        installedTables = List.copyOf(installedTables);
        skippedTables = Collections.unmodifiableMap(new TreeMap<>(skippedTables));
    }
}
